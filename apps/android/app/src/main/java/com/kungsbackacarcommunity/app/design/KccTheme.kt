package com.kungsbackacarcommunity.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * KCC Crown UI theme (contracts/design-tokens/tokens.json).
 *
 * Every Material3 color-scheme slot and text style used by components is
 * mapped onto contract tokens below so that no Material baseline (purple)
 * defaults can leak into the UI. The slot mapping is a hand-maintained
 * design decision — it composes EXISTING tokens only and never invents
 * new color values; update it deliberately if the mapping changes.
 *
 * Core slots:
 * - pageBackground    -> background
 * - surfaceBackground -> surface
 * - subtleBackground  -> surfaceVariant / secondaryContainer
 * - textPrimary       -> onBackground / onSurface
 * - textSecondary     -> onSurfaceVariant / secondary
 * - borderDefault     -> outline
 * - brandPrimary      -> primary + surfaceTint + inversePrimary
 *                        (onPrimary is inkBlack: dark text on gold)
 * - statusError       -> error
 * - statusSuccess / statusWarning have no Material3 slot and are exposed
 *   through [KccStatusColors] / [LocalKccStatusColors].
 *
 * Accessibility baseline: type sizes are sp (respect user font scaling);
 * Material3 enforces 48dp minimum interactive component size by default.
 */

@Immutable
data class KccStatusColors(
    val success: Color,
    val warning: Color,
)

val LocalKccStatusColors = staticCompositionLocalOf {
    KccStatusColors(
        success = KccPalette.successGreen,
        warning = KccPalette.warningAmber,
    )
}

/**
 * Whether the [KccTheme] currently in effect is the dark scheme.
 *
 * Read this instead of `isSystemInDarkTheme()` anywhere inside the app's theme:
 * the system value is only one input to the decision (the user's
 * [ThemePreference] can override it), so calling the system directly renders
 * against a theme the app is not actually using.
 *
 * Defaults to false for composables outside a [KccTheme] (previews, isolated
 * component tests); every real screen is inside one.
 */
val LocalKccDarkTheme = staticCompositionLocalOf { false }

/** Semantic map chrome and driver roles, independent from membership and rewards. */
@Immutable
data class KccMapColors(
    val overlay: Color,
    val border: Color,
    val driverActive: Color,
    val onDriverActive: Color,
)

val LocalKccMapColors = staticCompositionLocalOf {
    KccMapColors(KccLightColors.overlayBackground, KccLightColors.overlayBorder,
        KccLightColors.driverActive, KccLightColors.onDriverActive)
}

private val LightColorScheme = lightColorScheme(
    primary = KccLightColors.brandPrimary,
    onPrimary = KccLightColors.onBrandPrimary,
    primaryContainer = KccPalette.softSand,
    onPrimaryContainer = KccPalette.inkBlack,
    inversePrimary = KccPalette.crownGold,
    secondary = KccLightColors.textSecondary,
    onSecondary = KccPalette.warmIvory,
    secondaryContainer = KccLightColors.subtleBackground,
    onSecondaryContainer = KccPalette.inkBlack,
    tertiary = KccLightColors.driverActive,
    onTertiary = KccLightColors.onDriverActive,
    tertiaryContainer = KccLightColors.subtleBackground,
    onTertiaryContainer = KccLightColors.driverActive,
    background = KccLightColors.pageBackground,
    onBackground = KccLightColors.textPrimary,
    surface = KccLightColors.surfaceBackground,
    surfaceDim = KccLightColors.subtleBackground,
    surfaceBright = KccLightColors.surfaceBackground,
    surfaceContainerLowest = KccLightColors.pageBackground,
    surfaceContainerLow = KccLightColors.surfaceBackground,
    surfaceContainer = KccLightColors.surfaceBackground,
    surfaceContainerHigh = KccLightColors.surfaceBackground,
    surfaceContainerHighest = KccLightColors.subtleBackground,
    onSurface = KccLightColors.textPrimary,
    surfaceVariant = KccLightColors.subtleBackground,
    onSurfaceVariant = KccLightColors.textSecondary,
    surfaceTint = KccLightColors.surfaceBackground,
    inverseSurface = KccPalette.inkBlack,
    inverseOnSurface = KccPalette.warmIvory,
    error = KccLightColors.statusError,
    onError = KccPalette.warmIvory,
    errorContainer = KccPalette.softSand,
    onErrorContainer = KccLightColors.statusError,
    outline = KccLightColors.borderDefault,
    outlineVariant = KccPalette.softSand,
    scrim = KccPalette.inkBlack,
)

private val DarkColorScheme = darkColorScheme(
    primary = KccDarkColors.brandPrimary,
    onPrimary = KccDarkColors.onBrandPrimary,
    primaryContainer = KccDarkColors.subtleBackground,
    onPrimaryContainer = KccPalette.crownGold,
    inversePrimary = KccPalette.crownGold,
    secondary = KccDarkColors.textSecondary,
    onSecondary = KccPalette.inkBlack,
    secondaryContainer = KccDarkColors.subtleBackground,
    onSecondaryContainer = KccPalette.warmIvory,
    tertiary = KccDarkColors.driverActive,
    onTertiary = KccDarkColors.onDriverActive,
    tertiaryContainer = KccDarkColors.subtleBackground,
    onTertiaryContainer = KccDarkColors.driverActive,
    background = KccDarkColors.pageBackground,
    onBackground = KccDarkColors.textPrimary,
    surface = KccDarkColors.surfaceBackground,
    surfaceDim = KccDarkColors.subtleBackground,
    surfaceBright = KccDarkColors.surfaceBackground,
    surfaceContainerLowest = KccDarkColors.pageBackground,
    surfaceContainerLow = KccDarkColors.surfaceBackground,
    surfaceContainer = KccDarkColors.surfaceBackground,
    surfaceContainerHigh = KccDarkColors.surfaceBackground,
    surfaceContainerHighest = KccDarkColors.subtleBackground,
    onSurface = KccDarkColors.textPrimary,
    surfaceVariant = KccDarkColors.subtleBackground,
    onSurfaceVariant = KccDarkColors.textSecondary,
    surfaceTint = KccDarkColors.surfaceBackground,
    inverseSurface = KccPalette.warmIvory,
    inverseOnSurface = KccPalette.inkBlack,
    error = KccDarkColors.statusError,
    onError = KccPalette.warmIvory,
    errorContainer = KccDarkColors.subtleBackground,
    onErrorContainer = KccDarkColors.statusError,
    outline = KccDarkColors.borderDefault,
    outlineVariant = KccDarkColors.overlayBorder,
    scrim = KccPalette.inkBlack,
)

/**
 * Full Material3 type scale expressed with contract type tokens only
 * (5 sizes, 3 weights). Styles cluster onto the nearest token size so no
 * Material default sizes leak in; line heights use Compose defaults.
 */
private val KccTypography = Typography(
    displayLarge = TextStyle(fontSize = KccTypeScale.headingLg, fontWeight = KccTypeScale.semibold),
    displayMedium = TextStyle(fontSize = KccTypeScale.headingLg, fontWeight = KccTypeScale.semibold),
    displaySmall = TextStyle(fontSize = KccTypeScale.headingLg, fontWeight = KccTypeScale.semibold),
    headlineLarge = TextStyle(fontSize = KccTypeScale.headingLg, fontWeight = KccTypeScale.semibold),
    headlineMedium = TextStyle(fontSize = KccTypeScale.headingLg, fontWeight = KccTypeScale.medium),
    headlineSmall = TextStyle(fontSize = KccTypeScale.titleMd, fontWeight = KccTypeScale.semibold),
    titleLarge = TextStyle(fontSize = KccTypeScale.titleMd, fontWeight = KccTypeScale.semibold),
    titleMedium = TextStyle(fontSize = KccTypeScale.titleMd, fontWeight = KccTypeScale.medium),
    titleSmall = TextStyle(fontSize = KccTypeScale.bodyMd, fontWeight = KccTypeScale.medium),
    bodyLarge = TextStyle(fontSize = KccTypeScale.bodyMd, fontWeight = KccTypeScale.regular),
    bodyMedium = TextStyle(fontSize = KccTypeScale.bodySm, fontWeight = KccTypeScale.regular),
    bodySmall = TextStyle(fontSize = KccTypeScale.caption, fontWeight = KccTypeScale.regular),
    labelLarge = TextStyle(fontSize = KccTypeScale.bodySm, fontWeight = KccTypeScale.medium),
    labelMedium = TextStyle(fontSize = KccTypeScale.caption, fontWeight = KccTypeScale.medium),
    labelSmall = TextStyle(fontSize = KccTypeScale.caption, fontWeight = KccTypeScale.medium),
)

@Composable
fun KccTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val statusColors = KccStatusColors(
        success = if (darkTheme) KccDarkColors.statusSuccess else KccLightColors.statusSuccess,
        warning = if (darkTheme) KccDarkColors.statusWarning else KccLightColors.statusWarning,
    )
    CompositionLocalProvider(
        LocalKccStatusColors provides statusColors,
        // Publish the RESOLVED darkness so descendants that need to know
        // whether the app is currently dark (the map's day/night default) read
        // the theme actually in effect instead of calling isSystemInDarkTheme()
        // themselves. Without this they bypass the user's theme preference and
        // silently follow the system — which is the bug this replaced.
        LocalKccDarkTheme provides darkTheme,
        LocalKccMapColors provides if (darkTheme) {
            KccMapColors(KccDarkColors.overlayBackground, KccDarkColors.overlayBorder,
                KccDarkColors.driverActive, KccDarkColors.onDriverActive)
        } else {
            KccMapColors(KccLightColors.overlayBackground, KccLightColors.overlayBorder,
                KccLightColors.driverActive, KccLightColors.onDriverActive)
        },
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KccTypography,
            content = content,
        )
    }
}
