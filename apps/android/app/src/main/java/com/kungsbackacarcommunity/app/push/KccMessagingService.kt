package com.kungsbackacarcommunity.app.push

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kungsbackacarcommunity.app.BuildConfig
import com.kungsbackacarcommunity.app.MainActivity
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
 *   system notification only when a user is signed in AND POST_NOTIFICATIONS
 *   is granted ([PushDisplay.shouldDisplay]); silently drops it otherwise
 *   (the durable in-app inbox is the source of truth, so nothing is lost).
 *   The signed-in guard is defence in depth for shared devices: sign-out does
 *   unregister the token now, but that call can fail or race an in-flight
 *   send. It additionally drops notifications for the chat surface the member
 *   is currently looking at ([ActiveChatRegistry]).
 *
 * Tapping a notification carries its [PushDeepLink] to [MainActivity] as
 * Intent extras, which hands it to the shell via [PushNavigator].
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
        // Cheap and crash-safe: without an initialized FirebaseApp there is
        // no auth state to consult (and no legitimate delivery) — drop.
        if (FirebaseApp.getApps(applicationContext).isEmpty()) return
        val signedIn = FirebaseAuth.getInstance().currentUser != null
        val permissionGranted =
            currentPushPermissionStatus(applicationContext) == PushPermissionStatus.GRANTED
        if (!PushDisplay.shouldDisplay(signedIn = signedIn, permissionGranted = permissionGranted)) {
            return
        }

        val model =
            PushDisplay.fromMessage(
                data = message.data,
                notificationTitle = message.notification?.title,
                notificationBody = message.notification?.body,
            )

        // Don't buzz someone about the conversation already on their screen —
        // they are watching the message arrive. The inbox item still exists.
        if (ActiveChatRegistry.suppresses(model.deepLink)) return

        // Idempotent; also covers process starts that predate channel creation.
        PushChannels.ensureCreated(applicationContext)

        val notification =
            NotificationCompat.Builder(applicationContext, model.channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(
                    androidx.core.content.ContextCompat.getColor(
                        applicationContext,
                        R.color.kcc_crown_gold,
                    ),
                )
                .setContentTitle(model.title ?: getString(R.string.app_name))
                .setContentIntent(openAppIntent(model.deepLink))
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

    /**
     * Content intent that opens the app at [link] when the notification is
     * tapped. CLEAR_TOP | SINGLE_TOP reuses the existing task rather than
     * stacking a MainActivity, so the extras arrive via `onNewIntent` on a warm
     * app and via `onCreate` on a cold one — MainActivity handles both.
     *
     * The request code is derived from the link so that two notifications for
     * DIFFERENT destinations get distinct PendingIntents. With a constant code,
     * FLAG_UPDATE_CURRENT would rewrite the earlier notification's extras and
     * both would navigate to whichever arrived last.
     *
     * Without a content intent [NotificationCompat.setAutoCancel] has nothing
     * to fire, so the notification would also not dismiss on tap.
     */
    private fun openAppIntent(link: PushDeepLink): PendingIntent {
        val intent =
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PUSH_TARGET, link.target.wire)
                .putExtra(EXTRA_PUSH_ENTITY_ID, link.entityId)
        return PendingIntent.getActivity(
            applicationContext,
            link.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Intent extra: [PushTarget.wire] of the tapped notification. */
        const val EXTRA_PUSH_TARGET = "com.kungsbackacarcommunity.app.push.TARGET"

        /** Intent extra: the target's entity id, when it has one. */
        const val EXTRA_PUSH_ENTITY_ID = "com.kungsbackacarcommunity.app.push.ENTITY_ID"

        /**
         * Decodes the deep link a notification tap put on [intent], or null if
         * this intent did not come from a notification (a normal launcher
         * start, which must not navigate anywhere).
         */
        fun deepLinkFrom(intent: Intent?): PushDeepLink? {
            val target = intent?.getStringExtra(EXTRA_PUSH_TARGET) ?: return null
            return PushDeepLink(
                target = PushTarget.fromWire(target),
                entityId = intent.getStringExtra(EXTRA_PUSH_ENTITY_ID),
            )
        }
    }
}
