package com.kungsbackacarcommunity.app.driverfeed

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// Verified in Driver's android/app/build.gradle.kts. Driver currently exposes
// only its launcher; it has neither an external post route nor a published store URL.
internal const val DRIVER_ANDROID_PACKAGE = "com.kungsbackacarcommunity.driver"

/** Open the installed app without sending post content, identity, or invented routing extras. */
internal fun openDriverApp(context: Context): Boolean = try {
    // Attempt directly: package-visibility preflight queries are unnecessary.
    context.startActivity(Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setPackage(DRIVER_ANDROID_PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@Composable
internal fun driverAppLauncher(): () -> Boolean {
    val context = LocalContext.current
    return remember(context) { { openDriverApp(context) } }
}
