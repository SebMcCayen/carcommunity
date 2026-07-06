package com.kungsbackacarcommunity.app.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Thin Android glue for the notification-settings screen. The screen and route
 * stay platform-free (they take [PushPermissionStatus] + an open-settings
 * lambda); these helpers translate runtime state at the call site.
 */

/** Current runtime push-notification permission for display. */
fun currentPushPermissionStatus(context: Context): PushPermissionStatus =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) PushPermissionStatus.GRANTED else PushPermissionStatus.DENIED
    } else {
        // Pre-13 has no runtime permission; the channel-level toggle governs delivery.
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            PushPermissionStatus.GRANTED
        } else {
            PushPermissionStatus.DENIED
        }
    }

/** Opens this app's system notification settings so the user can grant/revoke push. */
fun openAppNotificationSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
