package me.rerere.ai.util

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.Locale

fun List<UIMessage>.hasImageGenerationIntent(): Boolean {
    val userMessage = lastOrNull { it.role == MessageRole.USER } ?: return false
    val text = userMessage.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .lowercase(Locale.ROOT)
    if (text.isBlank()) return false

    val hasImageInput = userMessage.parts.any { it is UIMessagePart.Image }
    if (DIRECT_IMAGE_GENERATION_TERMS.any { it in text }) return true
    if (IMAGE_GENERATION_VERBS.any { it in text } && IMAGE_TARGET_TERMS.any { it in text }) return true
    return hasImageInput && IMAGE_EDIT_TERMS.any { it in text }
}

private val DIRECT_IMAGE_GENERATION_TERMS = listOf(
    "text-to-image",
    "image-to-image",
    "img2img",
    "generate image",
    "generate an image",
    "create image",
    "create an image",
    "draw image",
    "edit image",
    "modify image",
    "\u751f\u56fe",
    "\u56fe\u751f\u56fe",
    "\u4ee5\u56fe\u751f\u56fe",
    "\u751f\u6210\u56fe\u7247",
    "\u751f\u6210\u56fe\u50cf",
    "\u56fe\u7247\u751f\u6210",
    "\u56fe\u7247\u8f93\u51fa",
    "\u751f\u6210\u4e00\u5f20",
    "\u753b\u4e00\u5f20",
    "\u753b\u56fe",
    "\u7ed8\u56fe",
    "\u7ed8\u5236\u56fe\u7247",
    "\u6539\u56fe",
    "\u4fee\u56fe",
)

private val IMAGE_GENERATION_VERBS = listOf(
    "generate",
    "create",
    "draw",
    "render",
    "design",
    "make",
    "edit",
    "modify",
    "change",
    "replace",
    "transform",
    "convert",
    "turn into",
    "\u751f\u6210",
    "\u521b\u5efa",
    "\u753b",
    "\u7ed8\u5236",
    "\u8bbe\u8ba1",
    "\u5236\u4f5c",
    "\u7f16\u8f91",
    "\u4fee\u6539",
    "\u66ff\u6362",
    "\u53d8\u6210",
    "\u6539\u6210",
    "\u8f6c\u6210",
)

private val IMAGE_TARGET_TERMS = listOf(
    "image",
    "picture",
    "photo",
    "poster",
    "illustration",
    "avatar",
    "wallpaper",
    "logo",
    "icon",
    "sticker",
    "\u56fe",
    "\u56fe\u7247",
    "\u56fe\u50cf",
    "\u7167\u7247",
    "\u6d77\u62a5",
    "\u63d2\u753b",
    "\u5934\u50cf",
    "\u58c1\u7eb8",
    "\u8d34\u7eb8",
)

private val IMAGE_EDIT_TERMS = listOf(
    "edit",
    "modify",
    "change",
    "replace",
    "transform",
    "convert",
    "turn into",
    "keep",
    "preserve",
    "remove",
    "add",
    "background",
    "style",
    "\u6539",
    "\u4fee",
    "\u66ff\u6362",
    "\u53d8\u6210",
    "\u6539\u6210",
    "\u8f6c\u6210",
    "\u4fdd\u7559",
    "\u4fdd\u6301",
    "\u53bb\u6389",
    "\u79fb\u9664",
    "\u52a0\u4e0a",
    "\u80cc\u666f",
    "\u98ce\u683c",
    "\u91cd\u753b",
)
