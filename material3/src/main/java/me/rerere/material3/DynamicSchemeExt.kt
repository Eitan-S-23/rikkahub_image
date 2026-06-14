package me.rerere.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dynamiccolor.DynamicColor
import dynamiccolor.DynamicScheme
import dynamiccolor.MaterialDynamicColors

private val materialDynamicColors = MaterialDynamicColors()

private fun DynamicScheme.color(dynamicColor: DynamicColor): Color = Color(dynamicColor.getArgb(this))

fun DynamicScheme.toColorScheme(): ColorScheme {
    val c = materialDynamicColors
    return if (isDark) {
        darkColorScheme(
            primary = color(c.primary()),
            onPrimary = color(c.onPrimary()),
            primaryContainer = color(c.primaryContainer()),
            onPrimaryContainer = color(c.onPrimaryContainer()),
            inversePrimary = color(c.inversePrimary()),
            secondary = color(c.secondary()),
            onSecondary = color(c.onSecondary()),
            secondaryContainer = color(c.secondaryContainer()),
            onSecondaryContainer = color(c.onSecondaryContainer()),
            tertiary = color(c.tertiary()),
            onTertiary = color(c.onTertiary()),
            tertiaryContainer = color(c.tertiaryContainer()),
            onTertiaryContainer = color(c.onTertiaryContainer()),
            background = color(c.background()),
            onBackground = color(c.onBackground()),
            surface = color(c.surface()),
            onSurface = color(c.onSurface()),
            surfaceVariant = color(c.surfaceVariant()),
            onSurfaceVariant = color(c.onSurfaceVariant()),
            surfaceTint = color(c.surfaceTint()),
            inverseSurface = color(c.inverseSurface()),
            inverseOnSurface = color(c.inverseOnSurface()),
            error = color(c.error()),
            onError = color(c.onError()),
            errorContainer = color(c.errorContainer()),
            onErrorContainer = color(c.onErrorContainer()),
            outline = color(c.outline()),
            outlineVariant = color(c.outlineVariant()),
            scrim = color(c.scrim()),
            surfaceBright = color(c.surfaceBright()),
            surfaceDim = color(c.surfaceDim()),
            surfaceContainer = color(c.surfaceContainer()),
            surfaceContainerHigh = color(c.surfaceContainerHigh()),
            surfaceContainerHighest = color(c.surfaceContainerHighest()),
            surfaceContainerLow = color(c.surfaceContainerLow()),
            surfaceContainerLowest = color(c.surfaceContainerLowest()),
            primaryFixed = color(c.primaryFixed()),
            primaryFixedDim = color(c.primaryFixedDim()),
            onPrimaryFixed = color(c.onPrimaryFixed()),
            onPrimaryFixedVariant = color(c.onPrimaryFixedVariant()),
            secondaryFixed = color(c.secondaryFixed()),
            secondaryFixedDim = color(c.secondaryFixedDim()),
            onSecondaryFixed = color(c.onSecondaryFixed()),
            onSecondaryFixedVariant = color(c.onSecondaryFixedVariant()),
            tertiaryFixed = color(c.tertiaryFixed()),
            tertiaryFixedDim = color(c.tertiaryFixedDim()),
            onTertiaryFixed = color(c.onTertiaryFixed()),
            onTertiaryFixedVariant = color(c.onTertiaryFixedVariant()),
        )
    } else {
        lightColorScheme(
            primary = color(c.primary()),
            onPrimary = color(c.onPrimary()),
            primaryContainer = color(c.primaryContainer()),
            onPrimaryContainer = color(c.onPrimaryContainer()),
            inversePrimary = color(c.inversePrimary()),
            secondary = color(c.secondary()),
            onSecondary = color(c.onSecondary()),
            secondaryContainer = color(c.secondaryContainer()),
            onSecondaryContainer = color(c.onSecondaryContainer()),
            tertiary = color(c.tertiary()),
            onTertiary = color(c.onTertiary()),
            tertiaryContainer = color(c.tertiaryContainer()),
            onTertiaryContainer = color(c.onTertiaryContainer()),
            background = color(c.background()),
            onBackground = color(c.onBackground()),
            surface = color(c.surface()),
            onSurface = color(c.onSurface()),
            surfaceVariant = color(c.surfaceVariant()),
            onSurfaceVariant = color(c.onSurfaceVariant()),
            surfaceTint = color(c.surfaceTint()),
            inverseSurface = color(c.inverseSurface()),
            inverseOnSurface = color(c.inverseOnSurface()),
            error = color(c.error()),
            onError = color(c.onError()),
            errorContainer = color(c.errorContainer()),
            onErrorContainer = color(c.onErrorContainer()),
            outline = color(c.outline()),
            outlineVariant = color(c.outlineVariant()),
            scrim = color(c.scrim()),
            surfaceBright = color(c.surfaceBright()),
            surfaceDim = color(c.surfaceDim()),
            surfaceContainer = color(c.surfaceContainer()),
            surfaceContainerHigh = color(c.surfaceContainerHigh()),
            surfaceContainerHighest = color(c.surfaceContainerHighest()),
            surfaceContainerLow = color(c.surfaceContainerLow()),
            surfaceContainerLowest = color(c.surfaceContainerLowest()),
            primaryFixed = color(c.primaryFixed()),
            primaryFixedDim = color(c.primaryFixedDim()),
            onPrimaryFixed = color(c.onPrimaryFixed()),
            onPrimaryFixedVariant = color(c.onPrimaryFixedVariant()),
            secondaryFixed = color(c.secondaryFixed()),
            secondaryFixedDim = color(c.secondaryFixedDim()),
            onSecondaryFixed = color(c.onSecondaryFixed()),
            onSecondaryFixedVariant = color(c.onSecondaryFixedVariant()),
            tertiaryFixed = color(c.tertiaryFixed()),
            tertiaryFixedDim = color(c.tertiaryFixedDim()),
            onTertiaryFixed = color(c.onTertiaryFixed()),
            onTertiaryFixedVariant = color(c.onTertiaryFixedVariant()),
        )
    }
}
