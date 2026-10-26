package com.kungsbackacarcommunity.app.garage

/** Preserve compact S57 density below 360dp of effective reading width. */
internal fun garagePhotoAspectRatio(widthDp: Float, fontScale: Float): Float {
    val scale = fontScale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    return if (widthDp / scale < 360f) 2.5f else 16f / 9f
}

internal fun garagePhotoTag(vehicleId: String): String = "garage_photo_$vehicleId"
