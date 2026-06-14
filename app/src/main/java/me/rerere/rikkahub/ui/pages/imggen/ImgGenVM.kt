package me.rerere.rikkahub.ui.pages.imggen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String
)

private fun GenMediaEntity.toGeneratedImage(filesManager: FilesManager): GeneratedImage {
    val imagesDir = filesManager.getImagesDir()
    val fullPath = File(imagesDir, this.path.removePrefix("images/")).absolutePath

    return GeneratedImage(
        id = this.id,
        prompt = this.prompt,
        filePath = fullPath,
        timestamp = this.createAt,
        model = this.modelId
    )
}

class ImgGenVM(
    context: Application,
    val settingsStore: SettingsStore,
    val providerManager: ProviderManager,
    val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
) : AndroidViewModel(context) {
    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _numberOfImages = MutableStateFlow(1)
    val numberOfImages: StateFlow<Int> = _numberOfImages

    private val _aspectRatio = MutableStateFlow(ImageAspectRatio.SQUARE)
    val aspectRatio: StateFlow<ImageAspectRatio> = _aspectRatio

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating
    private var cancelJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentGeneratedImages = MutableStateFlow<List<GeneratedImage>>(emptyList())
    val currentGeneratedImages: StateFlow<List<GeneratedImage>> = _currentGeneratedImages

    private val _referenceImages = MutableStateFlow<List<String>>(emptyList())
    val referenceImages: StateFlow<List<String>> = _referenceImages

    private val _debugInfo = MutableStateFlow("No image generation debug info yet.")
    val debugInfo: StateFlow<String> = _debugInfo

    val pager = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getAllMedia() }
    )
    val generatedImages: Flow<PagingData<GeneratedImage>> = pager.flow
        .map { pagingData ->
            pagingData.map { entity -> entity.toGeneratedImage(filesManager) }
        }
        .cachedIn(viewModelScope)

    fun updatePrompt(prompt: String) {
        _prompt.value = prompt
    }

    fun updateNumberOfImages(count: Int) {
        _numberOfImages.value = count.coerceIn(1, 4)
    }

    fun updateAspectRatio(aspectRatio: ImageAspectRatio) {
        _aspectRatio.value = aspectRatio
    }

    fun addReferenceImages(paths: List<String>) {
        _referenceImages.value = (_referenceImages.value + paths).distinct().take(MAX_REFERENCE_IMAGES)
    }

    fun removeReferenceImage(path: String) {
        _referenceImages.value = _referenceImages.value.filterNot { it == path }
        deleteReferenceFiles(listOf(path))
    }

    fun clearReferenceImages() {
        deleteReferenceFiles(_referenceImages.value)
        _referenceImages.value = emptyList()
    }

    fun clearError() {
        _error.value = null
    }

    fun refreshDebugInfo() {
        viewModelScope.launch {
            _debugInfo.value = runCatching {
                buildCurrentDebugInfo(trigger = "manual")
            }.getOrElse { error ->
                "Failed to build image generation debug info: ${error.message}"
            }
        }
    }

    fun startNewSession() {
        cancelJob?.cancel()
        clearReferenceImages()
        _prompt.value = ""
        _currentGeneratedImages.value = emptyList()
        _error.value = null
        _debugInfo.value = "No image generation debug info yet."
        _isGenerating.value = false
    }

    fun generateImage() {
        if(prompt.value.isBlank()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

                val providerSetting = settings.providers.find { it.id == provider.id }
                    ?: throw IllegalStateException("Provider setting not found")

                val requestPrompt = _prompt.value
                _debugInfo.value = buildDebugInfo(
                    trigger = "generateImage",
                    model = model,
                    providerSetting = providerSetting,
                    promptText = requestPrompt,
                    sourceImages = emptyList(),
                )
                val params = ImageGenerationParams(
                    model = model,
                    prompt = requestPrompt,
                    numOfImages = _numberOfImages.value,
                    aspectRatio = _aspectRatio.value,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies
                )

                val images = providerManager.getProviderByType(provider)
                    .generateImage(providerSetting, params)

                collectImageGeneration(
                    images = images,
                    prompt = requestPrompt,
                    modelName = model.displayName,
                )
            } catch (e: Exception) {
                if(e is CancellationException) return@launch
                Log.e(TAG, "Failed to generate image", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun editImage() {
        if (prompt.value.isBlank() || referenceImages.value.isEmpty()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

                val providerSetting = settings.providers.find { it.id == provider.id }
                    ?: throw IllegalStateException("Provider setting not found")

                val requestPrompt = _prompt.value
                val sourceImages = _referenceImages.value
                _debugInfo.value = buildDebugInfo(
                    trigger = "editImage",
                    model = model,
                    providerSetting = providerSetting,
                    promptText = requestPrompt,
                    sourceImages = sourceImages,
                )
                val params = ImageEditParams(
                    model = model,
                    prompt = requestPrompt,
                    images = sourceImages,
                    numOfImages = _numberOfImages.value,
                    aspectRatio = _aspectRatio.value,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies
                )

                val images = providerManager.getProviderByType(provider)
                    .editImage(providerSetting, params)

                collectImageGeneration(
                    images = images,
                    prompt = requestPrompt,
                    modelName = model.displayName,
                    type = GenMediaEntity.TYPE_IMAGE_EDIT,
                    sourcePaths = sourceImages.joinToString("\n"),
                )
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "Failed to edit image", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
    }

    private suspend fun collectImageGeneration(
        images: Flow<ImageGenerationItem>,
        prompt: String,
        modelName: String,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ) {
        val finalImages = mutableListOf<GeneratedImage>()
        var previewFile: File? = null
        var finalIndex = 0

        images.collect { item ->
            if (item.partial) {
                previewFile?.delete()
                val imageFile = saveImagePreview(
                    item = item,
                    modelName = modelName,
                    index = item.partialImageIndex ?: finalIndex,
                )
                previewFile = imageFile
                _currentGeneratedImages.value = finalImages + GeneratedImage(
                    id = 0,
                    prompt = prompt,
                    filePath = imageFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    model = modelName
                )
            } else {
                previewFile?.delete()
                previewFile = null
                val imageFile = saveImageToStorage(
                    item = item,
                    prompt = prompt,
                    modelName = modelName,
                    index = finalIndex,
                    type = type,
                    sourcePaths = sourcePaths,
                )
                finalImages.add(
                    GeneratedImage(
                        id = 0, // Will be updated after database insertion
                        prompt = prompt,
                        filePath = imageFile.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        model = modelName
                    )
                )
                finalIndex++
                _currentGeneratedImages.value = finalImages.toList()
            }
        }
    }

    private fun saveImagePreview(
        item: ImageGenerationItem,
        modelName: String,
        index: Int,
    ): File {
        val timestamp = System.currentTimeMillis()
        val imageFile = File(getApplication<Application>().appTempFolder, "imggen_${timestamp}_${modelName}_$index.png")
        return filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)
    }

    private suspend fun saveImageToStorage(
        item: ImageGenerationItem,
        prompt: String,
        modelName: String,
        index: Int,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ): File {
        val imagesDir = filesManager.getImagesDir()

        val timestamp = System.currentTimeMillis()
        val filename = "${timestamp}_${modelName}_$index.png"
        val imageFile = File(imagesDir, filename)

        val createdFile = filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)

        // Save to database with relative path
        val relativePath = "images/${imageFile.name}"
        val entity = GenMediaEntity(
            path = relativePath,
            modelId = modelName,
            prompt = prompt,
            createAt = timestamp,
            type = type,
            sourcePaths = sourcePaths,
        )
        genMediaRepository.insertMedia(entity)

        return createdFile
    }

    fun deleteImage(image: GeneratedImage) {
        viewModelScope.launch {
            try {
                // Delete from database first
                genMediaRepository.deleteMedia(image.id)

                // Then delete the file
                val file = File(image.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete image", e)
                _error.value = "Failed to delete image"
            }
        }
    }

    private fun deleteReferenceFiles(paths: List<String>) {
        viewModelScope.launch {
            paths.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    private suspend fun buildCurrentDebugInfo(trigger: String): String {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.imageGenerationModelId)
        val provider = model?.findProvider(settings.providers)
        val providerSetting = provider?.let { selectedProvider ->
            settings.providers.find { it.id == selectedProvider.id }
        }
        return buildDebugInfo(
            trigger = trigger,
            model = model,
            providerSetting = providerSetting,
            promptText = prompt.value,
            sourceImages = referenceImages.value,
        )
    }

    private suspend fun buildDebugInfo(
        trigger: String,
        model: Model?,
        providerSetting: ProviderSetting?,
        promptText: String,
        sourceImages: List<String>,
    ): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("RikkaHub Image Generation Debug")
            appendLine("appVersion=${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}")
            appendLine("trigger=$trigger")
            appendLine("willCall=${if (sourceImages.isEmpty()) "generateImage" else "editImage"}")
            appendLine("promptLength=${promptText.length}")
            appendLine("promptSha256=${promptText.toByteArray().sha256Short()}")
            appendLine("referenceImageCount=${sourceImages.size}")
            appendLine("numberOfImages=${numberOfImages.value}")
            appendLine("aspectRatio=${aspectRatio.value}")
            appendLine()

            appendLine("Model")
            appendLine("modelId=${model?.modelId.orEmpty()}")
            appendLine("displayName=${model?.displayName.orEmpty()}")
            appendLine("type=${model?.type}")
            appendLine("inputModalities=${model?.inputModalities.orEmpty()}")
            appendLine("outputModalities=${model?.outputModalities.orEmpty()}")
            appendLine("abilities=${model?.abilities.orEmpty()}")
            appendLine("customHeaderCount=${model?.customHeaders?.size ?: 0}")
            appendLine("customBody=${model?.customBodies?.debugSummary().orEmpty()}")
            appendLine()

            appendLine("Provider")
            when (providerSetting) {
                is ProviderSetting.OpenAI -> appendOpenAIDebug(providerSetting, sourceImages.isNotEmpty())
                is ProviderSetting.Google -> {
                    appendLine("type=Google")
                    appendLine("baseUrl=${providerSetting.baseUrl}")
                    appendLine("apiKeyConfigured=${providerSetting.apiKey.isNotBlank()}")
                }
                is ProviderSetting.Claude -> {
                    appendLine("type=Claude")
                    appendLine("baseUrl=${providerSetting.baseUrl}")
                    appendLine("apiKeyConfigured=${providerSetting.apiKey.isNotBlank()}")
                }
                null -> appendLine("type=<none>")
            }
            appendLine()

            appendLine("RequestShape")
            if (sourceImages.isEmpty()) {
                appendLine("messages[0].content=plain_text")
            } else {
                appendLine("messages[0].content[0]=text")
                sourceImages.forEachIndexed { index, image ->
                    appendLine("messages[0].content[${index + 1}]=image_url")
                    appendLine(image.toDebugImageInfo(index))
                }
            }
            appendLine("stream=false for OpenAI-compatible chat image requests")
            appendLine("fullBase64Included=false")
        }
    }

    private fun StringBuilder.appendOpenAIDebug(provider: ProviderSetting.OpenAI, isEdit: Boolean) {
        appendLine("type=OpenAI")
        appendLine("name=${provider.name}")
        appendLine("baseUrl=${provider.baseUrl}")
        appendLine("chatCompletionsPath=${provider.chatCompletionsPath}")
        appendLine("resolvedChatEndpoint=${provider.resolveChatEndpointForDebug()}")
        appendLine("useResponseApi=${provider.useResponseApi}")
        appendLine("includeHistoryReasoning=${provider.includeHistoryReasoning}")
        appendLine("apiKeyConfigured=${provider.apiKey.isNotBlank()}")
        appendLine("isOfficialOpenAI=${provider.baseUrl.contains("api.openai.com", ignoreCase = true)}")
        appendLine("hasChatEndpoint=${provider.hasChatEndpointForDebug()}")
        appendLine("expectedImageRoute=${provider.expectedImageRouteForDebug(isEdit)}")
        appendLine("providerModelCount=${provider.models.size}")
    }

    private fun ProviderSetting.OpenAI.expectedImageRouteForDebug(isEdit: Boolean): String {
        if (baseUrl.contains("api.openai.com", ignoreCase = true)) {
            return if (isEdit) "/images/edits" else "/images/generations"
        }
        if (hasChatEndpointForDebug()) {
            return resolveChatEndpointForDebug()
        }
        return if (isEdit) "/images/edits" else "/images/generations"
    }

    private fun ProviderSetting.OpenAI.hasChatEndpointForDebug(): Boolean {
        return baseUrl.contains("chat/completions", ignoreCase = true) ||
            chatCompletionsPath.contains("chat/completions", ignoreCase = true)
    }

    private fun ProviderSetting.OpenAI.resolveChatEndpointForDebug(): String {
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

    private fun List<CustomBody>.debugSummary(): String {
        if (isEmpty()) return "[]"
        return joinToString(prefix = "[", postfix = "]") { body ->
            val coreOverride = body.key in setOf("model", "messages", "stream", "modalities", "response_format")
            "${body.key}:${body.value.debugType()}${if (coreOverride) ":CORE_OVERRIDE" else ""}"
        }
    }

    private fun JsonElement.debugType(): String = when (this) {
        is JsonObject -> "object(keys=${keys.joinToString("|")})"
        is JsonArray -> "array(size=$size)"
        is JsonPrimitive -> "primitive"
    }

    private fun String.toDebugImageInfo(index: Int): String {
        val value = trim()
        return when {
            value.startsWith("data:image/") -> {
                val mime = value.substringAfter("data:").substringBefore(";")
                val base64Length = value.substringAfter("base64,", "").length
                "image[$index].kind=dataUrl mime=$mime base64Length=$base64Length " +
                    "dataSha256=${value.toByteArray().sha256Short()}"
            }
            value.startsWith("http://") || value.startsWith("https://") -> {
                "image[$index].kind=remoteUrl urlSha256=${value.toByteArray().sha256Short()}"
            }
            else -> {
                val file = runCatching {
                    if (value.startsWith("file://")) File(URI(value)) else File(value)
                }.getOrElse { File(value) }
                val exists = file.exists()
                val isFile = file.isFile
                val bytes = if (exists && isFile) runCatching { file.readBytes() }.getOrNull() else null
                val mime = file.imageMimeTypeByName()
                val base64Length = bytes?.let { ((it.size + 2) / 3) * 4 }
                "image[$index].kind=localFile path=${file.absolutePath} exists=$exists isFile=$isFile " +
                    "bytes=${bytes?.size ?: -1} mime=$mime dataUrlPrefix=data:$mime;base64, " +
                    "base64Length=${base64Length ?: -1} fileSha256=${bytes?.sha256Short().orEmpty()}"
            }
        }
    }

    private fun File.imageMimeTypeByName(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }

    private fun ByteArray.sha256Short(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val TAG = "ImgGenVM"
        private const val MAX_REFERENCE_IMAGES = 16
    }
}
