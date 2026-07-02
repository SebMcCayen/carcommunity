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
 * Semantic token -> Material3 slot mapping (a hand-maintained decision,
 * not a generated artifact — update deliberately if the mapping changes):
 * - pageBackground    -> background
 * - surfaceBackground -> surface
 * - subtleBackground  -> surfaceVariant
 * - textPrimary       -> onBackground / onSurface
 * - textSecondary     -> onSurfaceVariant
 * - borderDefault     -> outline
 * - brandPrimary      -> primary (onPrimary is inkBlack: dark text on gold)
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

private val LightColorScheme = lightColorScheme(
    primary = KccLightColors.brandPrimary,
    onPrimary = KccPalette.inkBlack,
    background = KccLightColors.pageBackground,
    onBackground = KccLightColors.textPrimary,
    surface = KccLightColors.surfaceBackground,
    onSurface = KccLightColors.textPrimary,
    surfaceVariant = KccLightColors.subtleBackground,
    onSurfaceVariant = KccLightColors.textSecondary,
    outline = KccLightColors.borderDefault,
    error = KccLightColors.statusError,
)

private val DarkColorScheme = darkColorScheme(
    primary = KccDarkColors.brandPrimary,
    onPrimary = KccPalette.inkBlack,
    background = KccDarkColors.pageBackground,
    onBackground = KccDarkColors.textPrimary,
    surface = KccDarkColors.surfaceBackground,
    onSurface = KccDarkColors.textPrimary,
    surfaceVariant = KccDarkColors.subtleBackground,
    onSurfaceVariant = KccDarkColors.textSecondary,
    outline = KccDarkColors.borderDefault,
    error = KccDarkColors.statusError,
)

private val KccTypography = Typography(
    headlineLarge = TextStyle(fontSize = KccTypeScale.headingLg, fontWeight = KccTypeScale.semibold),
    titleMedium = TextStyle(fontSize = KccTypeScale.titleMd, fontWeight = KccTypeScale.medium),
    bodyMedium = TextStyle(fontSize = KccTypeScale.bodyMd, fontWeight = KccTypeScale.regular),
    bodySmall = TextStyle(fontSize = KccTypeScale.bodySm, fontWeight = KccTypeScale.regular),
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
    CompositionLocalProvider(LocalKccStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KccTypography,
            content = content,
        )
    }
}
