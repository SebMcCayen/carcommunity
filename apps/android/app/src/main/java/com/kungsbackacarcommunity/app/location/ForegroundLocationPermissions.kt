package com.kungsbackacarcommunity.app.location

import android.Manifest

/** Android 12+ requires coarse and fine access in the SAME precise-location request. */
fun foregroundLocationPermissions(): Array<String> = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
)
