package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import me.rerere.material3.seedColorScheme
import kotlin.uuid.Uuid

@Serializable
data class CustomTheme(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val primaryColorArgb: Long = 0xFF6750A4,
    val secondaryColorArgb: Long? = null,
    val tertiaryColorArgb: Long? = null,
) {
    fun generateColorScheme(dark: Boolean): ColorScheme {
        return seedColorScheme(
            primarySeed = Color(primaryColorArgb.toInt()),
            dark = dark,
            secondarySeed = secondaryColorArgb?.let { Color(it.toInt()) },
            tertiarySeed = tertiaryColorArgb?.let { Color(it.toInt()) },
        )
    }
}
