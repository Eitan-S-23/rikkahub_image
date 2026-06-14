package me.rerere.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dynamiccolor.DynamicScheme
import palettes.TonalPalette

private fun TonalPalette.color(toneValue: Int): Color = Color(tone(toneValue))

fun DynamicScheme.toColorScheme(): ColorScheme {
    val p = primaryPalette
    val s = secondaryPalette
    val t = tertiaryPalette
    val n = neutralPalette
    val nv = neutralVariantPalette
    val e = errorPalette

    return if (isDark) {
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
            primaryFixed = p.color(90),
            primaryFixedDim = p.color(80),
            onPrimaryFixed = p.color(10),
            onPrimaryFixedVariant = p.color(30),
            secondaryFixed = s.color(90),
            secondaryFixedDim = s.color(80),
            onSecondaryFixed = s.color(10),
            onSecondaryFixedVariant = s.color(30),
            tertiaryFixed = t.color(90),
            tertiaryFixedDim = t.color(80),
            onTertiaryFixed = t.color(10),
            onTertiaryFixedVariant = t.color(30),
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
            primaryFixed = p.color(90),
            primaryFixedDim = p.color(80),
            onPrimaryFixed = p.color(10),
            onPrimaryFixedVariant = p.color(30),
            secondaryFixed = s.color(90),
            secondaryFixedDim = s.color(80),
            onSecondaryFixed = s.color(10),
            onSecondaryFixedVariant = s.color(30),
            tertiaryFixed = t.color(90),
            tertiaryFixedDim = t.color(80),
            onTertiaryFixed = t.color(10),
            onTertiaryFixedVariant = t.color(30),
        )
    }
}
