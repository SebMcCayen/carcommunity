// GENERATED FILE — do not edit by hand.
// Source: contracts/design-tokens/tokens.json
// Regenerate: node apps/android/scripts/generate-tokens.mjs
package com.kungsbackacarcommunity.app.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** KCC Crown UI brand palette. */
object KccPalette {
    val crownGold = Color(0xFFEAB54B)
    val darkCharcoal = Color(0xFF3F3E3B)
    val inkBlack = Color(0xFF040211)
    val warmIvory = Color(0xFFF8F6EF)
    val softSand = Color(0xFFF0EBDC)
    val mutedGrey = Color(0xFF6D6C6D)
    val silverGrey = Color(0xFFB4B1AD)
    val successGreen = Color(0xFF1E8E3E)
    val errorRed = Color(0xFFC5221F)
    val warningAmber = Color(0xFFE6A817)
}

/** Spacing scale (4pt base). */
object KccSpacing {
    val s0 = 0.dp
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s10 = 40.dp
    val s12 = 48.dp
}

/** Corner radius scale. */
object KccRadius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val full = 9999.dp
}

/** Type scale: sizes in sp (user-scalable) and font weights. */
object KccTypeScale {
    val caption = 12.sp
    val bodySm = 14.sp
    val bodyMd = 16.sp
    val titleMd = 18.sp
    val headingLg = 24.sp
    val regular = FontWeight.Normal
    val medium = FontWeight.Medium
    val semibold = FontWeight.SemiBold
}

/** Semantic light-theme colors. */
object KccLightColors {
    val pageBackground = Color(0xFFF8F6EF)
    val surfaceBackground = Color(0xFFFFFFFF)
    val subtleBackground = Color(0xFFF0EBDC)
    val textPrimary = Color(0xFF040211)
    val textSecondary = Color(0xFF6D6C6D)
    val borderDefault = Color(0xFFB4B1AD)
    val brandPrimary = Color(0xFFEAB54B)
    val statusSuccess = Color(0xFF1E8E3E)
    val statusError = Color(0xFFC5221F)
    val statusWarning = Color(0xFFE6A817)
}

/** Semantic dark-theme colors. */
object KccDarkColors {
    val pageBackground = Color(0xFF040211)
    val surfaceBackground = Color(0xFF3F3E3B)
    val subtleBackground = Color(0xFF2A2927)
    val textPrimary = Color(0xFFF8F6EF)
    val textSecondary = Color(0xFFB4B1AD)
    val borderDefault = Color(0xFF6D6C6D)
    val brandPrimary = Color(0xFFEAB54B)
    val statusSuccess = Color(0xFF1E8E3E)
    val statusError = Color(0xFFC5221F)
    val statusWarning = Color(0xFFE6A817)
}
