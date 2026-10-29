package com.kungsbackacarcommunity.app.chat

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Chat uses the existing primary/onPrimary gold pair for filled accents. On reading
 * surfaces, use gold only when it meets normal-text contrast; otherwise use the
 * theme's reading ink. Links keep their underline and mentions keep their weight.
 * This does not change the driver/map tertiary role or introduce another palette.
 */
internal fun ColorScheme.chatAccentTextColor(readingSurface: Color = surface): Color {
    val accentLuminance = primary.luminance()
    val surfaceLuminance = readingSurface.luminance()
    val contrast = (maxOf(accentLuminance, surfaceLuminance) + 0.05f) /
        (minOf(accentLuminance, surfaceLuminance) + 0.05f)
    return if (contrast >= 4.5f) primary else onSurface
}

/** Keep unread dots distinct even when their selected destination is gold. */
internal fun ColorScheme.chatUnreadIndicatorColor(selected: Boolean): Color =
    if (selected) onPrimary else chatAccentTextColor(background)
