package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.hasImageName
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import java.net.URI
import java.util.Base64
import kotlin.time.Clock

private const val TAG = "ChatCompletionsAPI"
private val IMAGE_RESPONSE_KEYS = listOf(
    "content",
    "text",
    "data",
    "images",
    "image",
    "source",
    "result",
    "output"
)
private val DATA_IMAGE_REGEX = Regex(
    pattern = "data:image/[^;\\s]+;base64,[A-Za-z0-9+/=\\s]+",
    option = RegexOption.IGNORE_CASE
)
private val MARKDOWN_IMAGE_REGEX = Regex("!\\[[^]]*]\\(([^)\\s]+)\\)")
private val HTTP_IMAGE_URL_REGEX = Regex(
    pattern = "https?://[^\\s<>\"]+\\.(?:png|jpe?g|webp|gif)(?:\\?[^\\s<>\"]*)?",
    option = RegexOption.IGNORE_CASE
)
private val BASE64_REGEX = Regex("^[A-Za-z0-9+/]+={0,2}$")

class ChatCompletionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody =
            buildChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting
            )

        val request = Request.Builder()
            .url(providerSetting.chatCompletionsEndpoint())
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val choice = bodyJson["choices"]?.jsonArray?.get(0)?.jsonObject ?: error("choices is null")

        val message = choice["message"]?.jsonObject ?: throw Exception("message is null")
        val finishReason = choice["finish_reason"]
            ?.jsonPrimitive
            ?.content
            ?: "unknown"
        val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

        MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(
                        jsonObject = message,
                        extraImageSources = imageResponseSources(choice, bodyJson),
                        extractLooseImageUrls = params.model.shouldParseLooseImageUrls()
                    ),
                    finishReason = finishReason
                )
            ),
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildChatCompletionRequest(
            messages = messages,
            params = params,
            providerSetting = providerSetting,
            stream = true,
        )

        val request = Request.Builder()
            .url(providerSetting.chatCompletionsEndpoint())
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        // just for debugging response body
        // println(client.newCall(request).await().body?.string())

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    println("[onEvent] (done) 结束流: $data")
                    close()
                    return
                }
                Log.d(TAG, "onEvent: $data")
                data
                    .trim()
                    .split("\n")
                    .filter { it.isNotBlank() }
                    .map { json.parseToJsonElement(it).jsonObject }
                    .forEach {
                        if (it["error"] != null) {
                            val error = it["error"]!!.parseErrorDetail()
                            throw error
                        }
                        val id = it["id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val model = it["model"]?.jsonPrimitive?.contentOrNull ?: ""

                        val choices = it["choices"]?.jsonArray ?: JsonArray(emptyList())
                        val choiceList = buildList {
                            if (choices.isNotEmpty()) {
                                val choice = choices[0].jsonObject
                                val message =
                                    choice["delta"]?.jsonObjectOrNull ?: choice["message"]?.jsonObjectOrNull
                                    ?: buildJsonObject {
                                        put("role", "assistant")
                                    }
                                val finishReason =
                                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                        ?: "unknown"
                                add(
                                    UIMessageChoice(
                                        index = 0,
                                        delta = parseMessage(
                                            jsonObject = message,
                                            extraImageSources = imageResponseSources(choice, it),
                                            extractLooseImageUrls = params.model.shouldParseLooseImageUrls()
                                        ),
                                        message = null,
                                        finishReason = finishReason,
                                    )
                                )
                            }
                        }
                        val usage = parseTokenUsage(it["usage"] as? JsonObject)

                        val messageChunk = MessageChunk(
                            id = id,
                            model = model,
                            choices = choiceList,
                            usage = usage
                        )
                        trySend(messageChunk)
                    }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        println(bodyElement)
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                    exception = e
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
    }


    suspend fun generateImage(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        val chunk = generateImageChatCompletion(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user(params.prompt)),
            params = TextGenerationParams(
                model = params.model,
                customHeaders = params.customHeaders,
                customBody = params.customBody,
            ),
        )
        emitGeneratedImages(chunk)
    }

    suspend fun editImage(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> = flow {
        val imageUrls = params.images.map { image -> image.toUploadImageUrl() }
        val textParams = TextGenerationParams(
            model = params.model,
            customHeaders = params.customHeaders,
            customBody = params.customBody,
        )
        val chunk = generateImageChatCompletion(
            providerSetting = providerSetting,
            requestBody = buildImageEditChatCompletionRequest(
                prompt = params.prompt,
                imageUrls = imageUrls,
                params = textParams,
            ),
            params = textParams,
            imageCount = imageUrls.size,
        )
        emitGeneratedImages(chunk)
    }

    private suspend fun generateImageChatCompletion(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        val requestBody =
            buildImageChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting,
            )
        val imageCount = messages.sumOf { message ->
            message.parts.count { part -> part is UIMessagePart.Image }
        }
        return generateImageChatCompletion(
            providerSetting = providerSetting,
            requestBody = requestBody,
            params = params,
            imageCount = imageCount,
        )
    }

    private suspend fun generateImageChatCompletion(
        providerSetting: ProviderSetting.OpenAI,
        requestBody: JsonObject,
        params: TextGenerationParams,
        imageCount: Int,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(providerSetting.chatCompletionsEndpoint())
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateImageChatCompletion: model=${params.model.modelId}, images=$imageCount")

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val choice = bodyJson["choices"]?.jsonArray?.get(0)?.jsonObject ?: error("choices is null")
        val message = choice["message"]?.jsonObject ?: throw Exception("message is null")
        val finishReason = choice["finish_reason"]
            ?.jsonPrimitive
            ?.content
            ?: "unknown"
        val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

        MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(
                        jsonObject = message,
                        extraImageSources = imageResponseSources(choice, bodyJson),
                        extractLooseImageUrls = params.model.shouldParseLooseImageUrls()
                    ),
                    finishReason = finishReason
                )
            ),
            usage = usage
        )
    }

    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        return buildJsonObject {
            put("model", params.model.modelId)
            put("messages", buildMessages(messages, providerSetting.includeHistoryReasoning))

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_tokens", params.maxTokens)

            put("stream", stream)
            if (stream) {
                if (host != "api.mistral.ai") { // mistral 不支持 stream_options
                    put("stream_options", buildJsonObject {
                        put("include_usage", true)
                    })
                }
            }

            // open router适配
            if (params.model.shouldRequestImageModalities()) {
                put("modalities", buildJsonArray {
                    add("image")
                    add("text")
                })
            }

            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                when (host) {
                    "openrouter.ai" -> {
                        // https://openrouter.ai/docs/use-cases/reasoning-tokens
                        put("reasoning", buildJsonObject {
                            when (level) {
                                ReasoningLevel.OFF -> put("effort", "none")
                                ReasoningLevel.AUTO -> put("enabled", true)
                                else -> put("effort", level.effort)
                            }
                        })
                    }

                    "dashscope.aliyuncs.com" -> {
                        // 阿里云百炼
                        // https://bailian.console.aliyun.com/console?tab=doc#/doc/?type=model&url=https%3A%2F%2Fhelp.aliyun.com%2Fdocument_detail%2F2870973.html&renderType=iframe
                        put("enable_thinking", level.isEnabled)
                        if (level != ReasoningLevel.AUTO) put("thinking_budget", level.budgetTokens)
                    }

                    "ark.cn-beijing.volces.com" -> {
                        // 豆包 (火山)
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.mistral.ai" -> {
                        // Mistral 不支持
                    }

                    "chat.intern-ai.org.cn" -> {
                        // 书生
                        // https://internlm.intern-ai.org.cn/api/document?lang=zh
                        put("thinking_mode", level.isEnabled)
                    }

                    "api.siliconflow.cn" -> {
                        // https://docs.siliconflow.cn/cn/userguide/capabilities/reasoning#3-1-api-%E5%8F%82%E6%95%B0
                        val modelId = params.model.modelId
                        val siliconflowThinkingModels = setOf(
                            "Pro/moonshotai/Kimi-K2.5",
                            "Pro/zai-org/GLM-5",
                            "Pro/zai-org/GLM-5.1",
                            "Pro/zai-org/GLM-4.7",
                            "deepseek-ai/DeepSeek-V3.2",
                            "Pro/deepseek-ai/DeepSeek-V3.2",
                            "Qwen/Qwen3.5-397B-A17B",
                            "Qwen/Qwen3.5-122B-A10B",
                            "Qwen/Qwen3.5-35B-A3B",
                            "Qwen/Qwen3.5-27B",
                            "Qwen/Qwen3.5-9B",
                            "Qwen/Qwen3.5-4B",
                            "zai-org/GLM-4.6",
                            "Qwen/Qwen3-8B",
                            "Qwen/Qwen3-14B",
                            "Qwen/Qwen3-32B",
                            "Qwen/Qwen3-30B-A3B",
                            "tencent/Hunyuan-A13B-Instruct",
                            "zai-org/GLM-4.5V",
                            "deepseek-ai/DeepSeek-V3.1-Terminus",
                            "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
                            "deepseek-ai/DeepSeek-V4-Flash",
                            "Pro/deepseek-ai/DeepSeek-V4-Flash",
                            "deepseek-ai/DeepSeek-V4-Pro",
                            "Pro/deepseek-ai/DeepSeek-V4-Pro",
                        )
                        if (modelId in siliconflowThinkingModels) {
                            put("enable_thinking", level.isEnabled)
                        }
                    }

                    "open.bigmodel.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.moonshot.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.deepseek.com" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                        if (level.isEnabled && level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", level.effort)
                        }
                    }

                    "integrate.api.nvidia.com" -> {
                        if ("deepseek-v4" in params.model.modelId.lowercase()) {
                            if (level != ReasoningLevel.AUTO) {
                                val effort = when (level) {
                                    ReasoningLevel.XHIGH -> "max"
                                    ReasoningLevel.OFF -> "none"
                                    else -> "high"
                                }
                                put("reasoning_effort", effort)
                            }
                        } else {
                            if (level != ReasoningLevel.AUTO) {
                                put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                            }
                        }
                    }

                    "opencode.ai" -> {
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", level.effort)
                        }
                    }

                    else -> {
                        // OpenAI 官方
                        // 文档中，completions API 只支持 "low", "medium", "high"
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                        }
                    }
                }
            }

            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                    )
                                )
                            })
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun buildImageChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
    ): JsonObject {
        return buildJsonObject {
            put("model", params.model.modelId)
            put("messages", buildMessages(messages, providerSetting.includeHistoryReasoning))
            put("stream", false)
        }.mergeCustomBody(params.customBody)
    }

    private fun buildImageEditChatCompletionRequest(
        prompt: String,
        imageUrls: List<String>,
        params: TextGenerationParams,
    ): JsonObject {
        return buildJsonObject {
            put("model", params.model.modelId)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        })
                        imageUrls.forEach { imageUrl ->
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", imageUrl)
                                })
                            })
                        }
                    }
                })
            }
            put("stream", false)
        }.mergeCustomBody(params.customBody)
    }

    private fun ProviderSetting.OpenAI.chatCompletionsEndpoint(): String {
        val base = baseUrl.trimEnd('/')
        val path = chatCompletionsPath.trim()
        return when {
            base.contains("chat/completions", ignoreCase = true) -> base
            path.startsWith("http://") || path.startsWith("https://") -> path
            path == "/chat/completions" && !base.endsWith("/v1") -> "$base/v1/chat/completions"
            path.isNotBlank() -> "$base/${path.trimStart('/')}"
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private fun isModelAllowTemperature(model: Model): Boolean {
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
    }

    private fun buildMessages(messages: List<UIMessage>, includeHistoryReasoning: Boolean = true) = buildJsonArray {
        val filteredMessages = messages.filter { it.isValidToUpload() }

        filteredMessages.forEach { message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(message, includeReasoning = includeHistoryReasoning)
            } else {
                addNonAssistantMessage(message)
            }
        }
    }

    private fun JsonArrayBuilder.addAssistantMessages(message: UIMessage, includeReasoning: Boolean) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()
        var reasoningPart: UIMessagePart.Reasoning? = null

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    // 从当前 group 中提取 reasoning（保持顺序）
                    if (includeReasoning) {
                        group.parts.filterIsInstance<UIMessagePart.Reasoning>().firstOrNull()?.let {
                            reasoningPart = it
                        }
                    }
                    group.parts
                        .filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
                        .forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 输出 assistant 消息（包含累积的内容 + tool_calls）
                    buildAssistantMessageJson(
                        contentParts = contentBuffer,
                        tools = group.tools,
                        reasoningPart = reasoningPart
                    )?.let { assistantMessage ->
                        add(assistantMessage)
                    }
                    contentBuffer.clear()
                    reasoningPart = null // 清空，下一个 group 可能有新的 reasoning

                    // 紧跟 tool 结果消息
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("name", tool.toolName)
                            put("tool_call_id", tool.toolCallId)
                            put(
                                "content",
                                tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty() || reasoningPart != null) {
            buildAssistantMessageJson(
                contentParts = contentBuffer,
                tools = emptyList(),
                reasoningPart = reasoningPart
            )?.let { assistantMessage ->
                add(assistantMessage)
            }
        }
    }

    private fun buildAssistantMessageJson(
        contentParts: List<UIMessagePart>,
        tools: List<UIMessagePart.Tool>,
        reasoningPart: UIMessagePart.Reasoning?
    ): JsonObject? {
        val hasUsableContent = contentParts.any { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.isNotBlank()
                is UIMessagePart.Image -> part.url.isNotBlank()
                else -> false
            }
        }
        val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank()
        if (!hasUsableContent && !hasReasoning && tools.isEmpty()) {
            return null
        }

        return buildJsonObject {
            put("role", "assistant")

            // reasoning_content
            if (hasReasoning) {
                put("reasoning_content", reasoningPart.reasoning)
            }

            // content
            if (contentParts.isEmpty()) {
                put("content", "")
            } else if (contentParts.size == 1 && contentParts[0] is UIMessagePart.Text) {
                put("content", (contentParts[0] as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    contentParts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "text")
                                        put("text", "")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }

            // tool_calls
            if (tools.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("id", tool.toolCallId)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.toolName)
                                put("arguments", tool.input)
                            })
                        })
                    }
                })
            }
        }
    }

    private fun JsonArrayBuilder.addNonAssistantMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", JsonPrimitive(message.role.name.lowercase()))

            if (message.parts.isOnlyTextPart()) {
                put("content", message.parts.filterIsInstance<UIMessagePart.Text>().first().text)
            } else {
                putJsonArray("content") {
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "text")
                                        put("text", "")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    private fun parseMessage(
        jsonObject: JsonObject,
        extraImageSources: List<JsonElement> = emptyList(),
        extractLooseImageUrls: Boolean = false
    ): UIMessage {
        val role = MessageRole.valueOf(
            jsonObject["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )

        // 也许支持其他模态的输出content?
        val contentElement = jsonObject["content"]
        val content = contentElement?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val contentImageParts = collectImageParts(contentElement, extractLooseImageUrls)
        val reasoning = jsonObject["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: contentElement?.takeIf { it is JsonArray }?.let { arr ->
                // Mistral接口
                // {"id":"","object":"chat.completion.chunk","created":1772351733,"model":"magistral-medium-2509","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"好的"}]}]},"finish_reason":null}]}
                arr.jsonArrayOrNull?.getOrNull(0)?.jsonObject?.get("thinking")?.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull?.get(
                    "text"
                )?.jsonPrimitiveOrNull?.contentOrNull
            }
        val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
        val images = jsonObject["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                val seenImageUrls = linkedSetOf<String>()
                fun addImagePart(image: UIMessagePart.Image) {
                    val url = image.url.normalizeGeneratedImageUrl() ?: return
                    if (url.isNotEmpty() && seenImageUrls.add(url)) {
                        add(image.copy(url = url))
                    }
                }

                if (!reasoning.isNullOrEmpty()) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                    )
                }
                toolCalls.forEach { toolCalls ->
                    val type = toolCalls.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    if (!type.isNullOrEmpty() && type != "function") error("tool call type not supported: $type")
                    val toolCallId = toolCalls.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    val toolName =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val arguments =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull
                    add(
                        UIMessagePart.Tool(
                            toolCallId = toolCallId ?: "",
                            toolName = toolName ?: "",
                            input = arguments ?: "",
                            output = emptyList()
                        )
                    )
                }
                if (content.isNotEmpty() && !content.shouldHideExtractedImagePayload(contentImageParts)) {
                    add(UIMessagePart.Text(content))
                }
                contentElement?.jsonArrayOrNull?.forEach { contentPart ->
                    val contentObject = contentPart.jsonObjectOrNull ?: return@forEach
                    when (contentObject["type"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> {
                            val text = contentObject["text"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
                            if (text.isNotBlank()) add(UIMessagePart.Text(text))
                        }

                        "image_url" -> {
                            contentObject.toImagePart()?.let(::addImagePart)
                        }
                    }
                }
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    imageObject.toImagePart()?.let(::addImagePart)
                }
                contentImageParts.forEach(::addImagePart)
                (listOf<JsonElement>(jsonObject) + extraImageSources).forEach { source ->
                    collectImageParts(source, extractLooseImageUrls).forEach(::addImagePart)
                }
            },
            annotations = parseAnnotations(
                jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(
                    emptyList()
                )
            ),
        )
    }

    private fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
        return jsonArray.map { element ->
            val type =
                element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
            when (type) {
                "url_citation" -> {
                    UIMessageAnnotation.UrlCitation(
                        title = element.jsonObject["url_citation"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "",
                        url = element.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: "",
                    )
                }

                else -> error("unknown annotation type: $type")
            }
        }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }

    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }

    private suspend fun FlowCollector<ImageGenerationItem>.emitGeneratedImages(chunk: MessageChunk) {
        val messages = chunk.choices.flatMap { choice -> listOfNotNull(choice.message, choice.delta) }
        val generatedImages = messages
            .flatMap { message -> message.parts }
            .filterIsInstance<UIMessagePart.Image>()
            .map { image -> image.toImageGenerationItem() }

        if (generatedImages.isEmpty()) {
            val text = messages.joinToString("\n") { message -> message.toText() }
                .ifBlank { "No image returned from chat completions" }
            error(text)
        }

        generatedImages.forEach { emit(it) }
    }

    private fun UIMessagePart.Image.toImageGenerationItem(): ImageGenerationItem {
        val mimeType = if (url.startsWith("data:image")) {
            url.substringAfter("data:").substringBefore(";")
        } else {
            "image/png"
        }
        val data = if (url.startsWith("data:image")) {
            url.substringAfter("base64,")
        } else {
            url
        }
        return ImageGenerationItem(
            data = data,
            mimeType = mimeType,
        )
    }

    private fun imageResponseSources(choice: JsonObject, body: JsonObject? = null): List<JsonElement> = buildList {
        IMAGE_RESPONSE_KEYS.forEach { key ->
            choice[key]?.let(::add)
        }
        body?.let { jsonObject ->
            IMAGE_RESPONSE_KEYS.forEach { key ->
                jsonObject[key]?.let(::add)
            }
        }
    }

    private fun collectImageParts(
        element: JsonElement?,
        extractLooseImageUrls: Boolean = false,
    ): List<UIMessagePart.Image> {
        val urls = mutableListOf<String>()
        collectImageUrls(element, urls, extractLooseImageUrls)
        return urls.distinct().map { url -> UIMessagePart.Image(url) }
    }

    private fun collectImageUrls(
        element: JsonElement?,
        urls: MutableList<String>,
        extractLooseImageUrls: Boolean,
    ) {
        when (element) {
            null -> return
            is JsonArray -> element.forEach { item -> collectImageUrls(item, urls, extractLooseImageUrls) }
            is JsonObject -> {
                collectBase64Image(element["b64_json"], urls)
                collectBase64Image(element["base64"], urls)
                collectUrlValue(element["url"], urls)

                when (val imageUrl = element["image_url"]) {
                    is JsonObject, is JsonArray -> collectImageUrls(imageUrl, urls, extractLooseImageUrls)
                    else -> collectUrlValue(imageUrl, urls)
                }

                IMAGE_RESPONSE_KEYS.forEach { key ->
                    element[key]?.let { value -> collectImageUrls(value, urls, extractLooseImageUrls) }
                }
            }
            is JsonPrimitive -> {
                element.contentOrNull?.let { text -> collectImageUrlsFromText(text, urls, extractLooseImageUrls) }
            }
        }
    }

    private fun collectBase64Image(element: JsonElement?, urls: MutableList<String>) {
        val value = element?.jsonPrimitiveOrNull?.contentOrNull?.trim().orEmpty()
        if (value.isBlank()) return
        if (value.startsWith("data:", ignoreCase = true)) {
            urls += value.replace(Regex("\\s+"), "")
            return
        }
        val normalized = value.replace(Regex("\\s+"), "")
        val mimeType = normalized.base64ImageMimeType() ?: "image/png"
        urls += "data:$mimeType;base64,$normalized"
    }

    private fun collectUrlValue(element: JsonElement?, urls: MutableList<String>) {
        val value = element?.jsonPrimitiveOrNull?.contentOrNull?.trim().orEmpty()
        if (value.isBlank()) return
        urls += if (value.startsWith("data:image/", ignoreCase = true)) {
            value.replace(Regex("\\s+"), "")
        } else {
            value
        }
    }

    private fun String.normalizeGeneratedImageUrl(): String? {
        val value = trim()
        if (value.isBlank()) return null
        if (value.startsWith("data:image/", ignoreCase = true)) {
            return value.replace(Regex("\\s+"), "")
        }
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("file://", ignoreCase = true)
        ) {
            return value
        }

        val normalized = value.replace(Regex("\\s+"), "")
        val mimeType = normalized.base64ImageMimeType() ?: return null
        return "data:$mimeType;base64,$normalized"
    }

    private fun String.shouldHideExtractedImagePayload(images: List<UIMessagePart.Image>): Boolean {
        if (images.isEmpty()) return false
        val text = stripCodeFence()
        return text.startsWith("{") ||
            text.startsWith("[") ||
            text.startsWith("data:image/", ignoreCase = true) ||
            text.base64ImageMimeType() != null ||
            MARKDOWN_IMAGE_REGEX.containsMatchIn(text) ||
            ((text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) &&
                !text.contains(Regex("\\s")))
    }

    private fun collectImageUrlsFromText(
        raw: String,
        urls: MutableList<String>,
        extractLooseImageUrls: Boolean,
    ) {
        val text = raw.trim()
        if (text.isBlank()) return

        val possibleJson = text.stripCodeFence()
        if (possibleJson.startsWith("{") || possibleJson.startsWith("[")) {
            val parsed = runCatching { json.parseToJsonElement(possibleJson) }.getOrNull()
            if (parsed != null) {
                collectImageUrls(parsed, urls, extractLooseImageUrls)
                return
            }
        }

        DATA_IMAGE_REGEX.findAll(text).forEach { match ->
            urls += match.value.replace(Regex("\\s+"), "")
        }
        MARKDOWN_IMAGE_REGEX.findAll(text).forEach { match ->
            urls += match.groupValues[1].trim()
        }
        HTTP_IMAGE_URL_REGEX.findAll(text).forEach { match ->
            urls += match.value.trimEnd(')', '.', ',', ';', '"', '\'')
        }
        if (extractLooseImageUrls &&
            (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true))
        ) {
            if (!text.contains(Regex("\\s"))) {
                urls += text.trimEnd(')', '.', ',', ';', '"', '\'')
            }
        }

        val normalized = text.replace(Regex("\\s+"), "")
        val mimeType = normalized.base64ImageMimeType() ?: return
        urls += "data:$mimeType;base64,$normalized"
    }

    private fun String.stripCodeFence(): String {
        val trimmed = trim()
        if (!trimmed.startsWith("```")) return trimmed

        val lines = trimmed.lines()
        if (lines.size < 2) return trimmed

        val withoutOpeningFence = lines.drop(1)
        val withoutClosingFence = if (withoutOpeningFence.lastOrNull()?.trim() == "```") {
            withoutOpeningFence.dropLast(1)
        } else {
            withoutOpeningFence
        }
        return withoutClosingFence.joinToString("\n").trim()
    }

    private fun String.base64ImageMimeType(): String? {
        if (length < 128 || !BASE64_REGEX.matches(this)) return null
        val sampleLength = minOf(length - length % 4, 128)
        if (sampleLength <= 0) return null

        val bytes = runCatching {
            Base64.getDecoder().decode(take(sampleLength))
        }.getOrNull() ?: return null

        return when {
            bytes.startsWithBytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            bytes.startsWithBytes(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            bytes.startsWithBytes(0x47, 0x49, 0x46, 0x38) -> "image/gif"
            bytes.size >= 12 &&
                bytes.startsWithBytes(0x52, 0x49, 0x46, 0x46) &&
                bytes[8] == 0x57.toByte() &&
                bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() &&
                bytes[11] == 0x50.toByte() -> "image/webp"
            else -> null
        }
    }

    private fun ByteArray.startsWithBytes(vararg prefix: Int): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { index -> this[index] == prefix[index].toByte() }
    }

    private fun Model.shouldRequestImageModalities(): Boolean =
        type == ModelType.IMAGE ||
            Modality.IMAGE in outputModalities

    private fun Model.shouldParseLooseImageUrls(): Boolean =
        shouldRequestImageModalities() ||
            hasImageName()

    private fun JsonObject.toImagePart(): UIMessagePart.Image? {
        val type = this["type"]?.jsonPrimitive?.contentOrNull ?: return null
        if (type != "image_url") return null
        val imageUrl = this["image_url"]
        val url = imageUrl?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull
            ?: imageUrl?.jsonPrimitiveOrNull?.contentOrNull
            ?: return null
        return UIMessagePart.Image(url)
    }

    private fun String.toUploadImageUrl(): String = when {
        startsWith("data:") || startsWith("http://") || startsWith("https://") -> this
        startsWith("file://") -> File(URI(this)).toDataUrl()
        else -> File(this).toDataUrl()
    }

    private fun File.toDataUrl(): String {
        require(exists()) { "Image file does not exist: $absolutePath" }
        require(isFile) { "Image path is not a file: $absolutePath" }

        val base64 = Base64.getEncoder().encodeToString(readBytes())
        return "data:${imageMimeTypeByName()};base64,$base64"
    }

    private fun File.imageMimeTypeByName(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }

}
