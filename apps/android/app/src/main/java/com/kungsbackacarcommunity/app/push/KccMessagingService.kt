package com.kungsbackacarcommunity.app.push

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kungsbackacarcommunity.app.BuildConfig
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.notifications.PushPermissionStatus
import com.kungsbackacarcommunity.app.notifications.currentPushPermissionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * FCM entry points (Phase 12 slice 21, push portion).
 *
 * - [onNewToken]: re-registers the rotated token via the guarded callable
 *   repository, but only when a user is signed in — the register callable
 *   requires an authenticated, active actor (pushTokens.ts). Signed-out
 *   rotations are picked up by the sign-in-time registration instead.
 * - [onMessageReceived]: maps the message through [PushDisplay] and posts a
 *   system notification when POST_NOTIFICATIONS is granted; silently drops
 *   it otherwise (the durable in-app inbox is the source of truth).
 *
 * Config-less safety: FCM only delivers when google-services.json is present,
 * so this service never runs in CI builds; every Firebase touch is still
 * guarded so an unexpected start is a clean no-op.
 */
class KccMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        if (FirebaseApp.getApps(applicationContext).isEmpty()) return
        // Registration requires a signed-in user; skip otherwise.
        if (FirebaseAuth.getInstance().currentUser == null) return
        val repository =
            FirebasePushTokenRepository.createIfAvailable(
                applicationContext,
                appVersion = BuildConfig.VERSION_NAME,
                buildNumber = BuildConfig.VERSION_CODE.toString(),
            ) ?: return
        scope.launch {
            try {
                repository.register(token)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Best-effort: a failed re-register is retried at next sign-in-
                // time registration. Details may reference the token — never log.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (currentPushPermissionStatus(applicationContext) != PushPermissionStatus.GRANTED) return

        val model =
            PushDisplay.fromMessage(
                data = message.data,
                notificationTitle = message.notification?.title,
                notificationBody = message.notification?.body,
            )

        // Idempotent; also covers process starts that predate channel creation.
        PushChannels.ensureCreated(applicationContext)

        val notification =
            NotificationCompat.Builder(applicationContext, model.channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(model.title ?: getString(R.string.app_name))
                .setAutoCancel(true)
                .apply { model.body?.let { setContentText(it) } }
                .build()

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(model.notificationId?.hashCode() ?: message.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post — drop silently.
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
