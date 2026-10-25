package com.kungsbackacarcommunity.app.badges

import kotlin.math.floor

/** Keep labels and touch targets readable as the window narrows or text grows. */
internal fun badgeGridColumns(widthDp: Float, fontScale: Float, preferredColumns: Int): Int {
    val preferred = preferredColumns.coerceAtLeast(1)
    val scale = fontScale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    val width = widthDp.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    // 96dp per tile plus the existing 8dp inter-column gutter.
    return floor((width + 8f) / (96f * scale + 8f)).toInt().coerceIn(1, preferred)
}
