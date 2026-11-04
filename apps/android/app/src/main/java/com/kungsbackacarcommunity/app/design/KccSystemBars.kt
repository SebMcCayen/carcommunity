package com.kungsbackacarcommunity.app.design

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb

/**
 * Keep the map visible behind transparent status bars and a 25% theme-surface
 * navigation scrim. Explicit styles follow the displayed app theme (including
 * forced-dark sign-in), rather than the phone's appearance setting.
 *
 * AndroidX uses inset-aware color protection on Android 15+ and the appropriate
 * platform backport on older devices. It also manages icon contrast, display
 * cutouts and navigation-bar contrast enforcement. Reapplying updates the same
 * protection instead of stacking another scrim on each theme change.
 */
internal fun ComponentActivity.applyKccSystemBars(darkTheme: Boolean) {
    val surface = if (darkTheme) KccDarkColors.surfaceBackground else KccLightColors.surfaceBackground
    val scrim = surface.copy(alpha = 0.25f).toArgb()
    enableEdgeToEdge(
        statusBarStyle = if (darkTheme) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        },
        navigationBarStyle = if (darkTheme) {
            SystemBarStyle.dark(scrim)
        } else {
            SystemBarStyle.light(scrim, scrim)
        },
    )
}
