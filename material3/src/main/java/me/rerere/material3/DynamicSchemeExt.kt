package me.rerere.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

private data class Hsl(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
)

private data class TonalPalette(
    val hue: Float,
    val saturation: Float,
) {
    fun color(toneValue: Int): Color {
        return Hsl(
            hue = hue,
            saturation = saturation,
            lightness = (toneValue / 100f).coerceIn(0f, 1f),
        ).toColor()
    }
}

fun seedColorScheme(
    primarySeed: Color,
    dark: Boolean,
    secondarySeed: Color? = null,
    tertiarySeed: Color? = null,
): ColorScheme {
    val primaryHsl = primarySeed.toHsl()
    val p = primarySeed.toTonalPalette(saturationScale = 0.92f, minSaturation = 0.28f)
    val s = secondarySeed?.toTonalPalette(saturationScale = 0.75f, minSaturation = 0.16f)
        ?: TonalPalette(
            hue = primaryHsl.hue,
            saturation = (primaryHsl.saturation * 0.35f).coerceIn(0.14f, 0.42f),
        )
    val t = tertiarySeed?.toTonalPalette(saturationScale = 0.85f, minSaturation = 0.18f)
        ?: TonalPalette(
            hue = primaryHsl.rotate(60f).hue,
            saturation = (primaryHsl.saturation * 0.55f).coerceIn(0.20f, 0.58f),
        )
    val n = TonalPalette(
        hue = primaryHsl.hue,
        saturation = (primaryHsl.saturation * 0.08f).coerceIn(0.02f, 0.08f),
    )
    val nv = TonalPalette(
        hue = primaryHsl.hue,
        saturation = (primaryHsl.saturation * 0.16f).coerceIn(0.04f, 0.14f),
    )
    val e = Color(0xFFBA1A1A.toInt()).toTonalPalette(saturationScale = 1f, minSaturation = 0.55f)

    return if (dark) {
        darkColorScheme(
            primary = p.color(80),
            onPrimary = p.color(20),
            primaryContainer = p.color(30),
            onPrimaryContainer = p.color(90),
            inversePrimary = p.color(40),
            secondary = s.color(80),
            onSecondary = s.color(20),
            secondaryContainer = s.color(30),
            onSecondaryContainer = s.color(90),
            tertiary = t.color(80),
            onTertiary = t.color(20),
            tertiaryContainer = t.color(30),
            onTertiaryContainer = t.color(90),
            background = n.color(6),
            onBackground = n.color(90),
            surface = n.color(6),
            onSurface = n.color(90),
            surfaceVariant = nv.color(30),
            onSurfaceVariant = nv.color(80),
            surfaceTint = p.color(80),
            inverseSurface = n.color(90),
            inverseOnSurface = n.color(20),
            error = e.color(80),
            onError = e.color(20),
            errorContainer = e.color(30),
            onErrorContainer = e.color(90),
            outline = nv.color(60),
            outlineVariant = nv.color(30),
            scrim = Color.Black,
            surfaceBright = n.color(24),
            surfaceDim = n.color(6),
            surfaceContainer = n.color(12),
            surfaceContainerHigh = n.color(17),
            surfaceContainerHighest = n.color(22),
            surfaceContainerLow = n.color(10),
            surfaceContainerLowest = n.color(4),
        )
    } else {
        lightColorScheme(
            primary = p.color(40),
            onPrimary = p.color(100),
            primaryContainer = p.color(90),
            onPrimaryContainer = p.color(10),
            inversePrimary = p.color(80),
            secondary = s.color(40),
            onSecondary = s.color(100),
            secondaryContainer = s.color(90),
            onSecondaryContainer = s.color(10),
            tertiary = t.color(40),
            onTertiary = t.color(100),
            tertiaryContainer = t.color(90),
            onTertiaryContainer = t.color(10),
            background = n.color(99),
            onBackground = n.color(10),
            surface = n.color(99),
            onSurface = n.color(10),
            surfaceVariant = nv.color(90),
            onSurfaceVariant = nv.color(30),
            surfaceTint = p.color(40),
            inverseSurface = n.color(20),
            inverseOnSurface = n.color(95),
            error = e.color(40),
            onError = e.color(100),
            errorContainer = e.color(90),
            onErrorContainer = e.color(10),
            outline = nv.color(50),
            outlineVariant = nv.color(80),
            scrim = Color.Black,
            surfaceBright = n.color(98),
            surfaceDim = n.color(87),
            surfaceContainer = n.color(94),
            surfaceContainerHigh = n.color(92),
            surfaceContainerHighest = n.color(90),
            surfaceContainerLow = n.color(96),
            surfaceContainerLowest = n.color(100),
        )
    }
}

private fun Color.toTonalPalette(
    saturationScale: Float,
    minSaturation: Float,
): TonalPalette {
    val hsl = toHsl()
    val saturation = if (hsl.saturation == 0f) {
        minSaturation
    } else {
        (hsl.saturation * saturationScale).coerceAtLeast(minSaturation)
    }
    return TonalPalette(
        hue = hsl.hue,
        saturation = saturation.coerceIn(0f, 1f),
    )
}

private fun Color.toHsl(): Hsl {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val lightness = (max + min) / 2f

    if (delta == 0f) {
        return Hsl(hue = 0f, saturation = 0f, lightness = lightness)
    }

    val saturation = delta / (1f - abs(2f * lightness - 1f))
    val hue = when (max) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }.let(::normalizeHue)

    return Hsl(
        hue = hue,
        saturation = saturation.coerceIn(0f, 1f),
        lightness = lightness.coerceIn(0f, 1f),
    )
}

private fun Hsl.toColor(): Color {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val huePrime = hue / 60f
    val x = chroma * (1f - abs((huePrime % 2f) - 1f))
    val (redPrime, greenPrime, bluePrime) = when {
        huePrime < 1f -> Triple(chroma, x, 0f)
        huePrime < 2f -> Triple(x, chroma, 0f)
        huePrime < 3f -> Triple(0f, chroma, x)
        huePrime < 4f -> Triple(0f, x, chroma)
        huePrime < 5f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val match = lightness - chroma / 2f

    return Color(
        red = (redPrime + match).coerceIn(0f, 1f),
        green = (greenPrime + match).coerceIn(0f, 1f),
        blue = (bluePrime + match).coerceIn(0f, 1f),
    )
}

private fun Hsl.rotate(degrees: Float): Hsl {
    return copy(hue = normalizeHue(hue + degrees))
}

private fun normalizeHue(hue: Float): Float {
    return ((hue % 360f) + 360f) % 360f
}
