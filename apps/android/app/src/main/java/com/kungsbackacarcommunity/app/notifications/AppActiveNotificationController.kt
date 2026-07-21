package com.kungsbackacarcommunity.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kungsbackacarcommunity.app.MainActivity
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.location.LocationSharingService

/**
 * Posts an unobtrusive, ongoing "app is active" notification while the app
 * process is in the foreground, and keeps it in the shade after the member
 * leaves the app — so, per Seb's request, "if you leave the app you still see
 * that it is active." It carries a tap intent back into the app.
 *
 * ## Why a plain notification and not a foreground service
 * The reliable Android primitive whose notification auto-clears the instant the
 * process dies is a foreground service — but #495 already runs one for live
 * location, declared `foregroundServiceType="location"`, which carries the Play
 * background-location obligation. A pure status indicator is NOT a location
 * service and must not borrow that type; and a second FGS of any other type
 * (specialUse/none) is heavier, Android-14-restricted, and would put a competing
 * ongoing notification next to the live-share one. So this is a plain ongoing
 * notification driven by [androidx.lifecycle.ProcessLifecycleOwner]:
 *
 * - [onStart] (app enters foreground, incl. first launch): re-evaluate → post.
 * - [onStop] (app leaves foreground): re-evaluate → keep it posted (the whole
 *   point is that it stays visible while backgrounded).
 *
 * Both transitions call [refresh], so the notice also reconciles against the
 * live-share session state on every foreground/background edge.
 *
 * ## Rotation vs real exit
 * ProcessLifecycleOwner already debounces configuration changes — a rotation
 * does NOT drive ON_STOP — so the notice survives Activity recreation and is
 * only re-evaluated on genuine app foreground/background.
 *
 * ## Known limitation (no device to verify)
 * A serviceless notification cannot run code at process death, so `notify()`d
 * ongoing notifications are not guaranteed to vanish the instant the app is
 * swiped from recents on every OEM (a force-stop from system settings does
 * clear them). The notice is idempotent — same channel + id — so the next
 * launch's [onStart] simply reconciles it. This is the accepted trade-off for
 * not shipping a second foreground service. Lifecycle wiring and posting are
 * instrumentation-only; the suppression decision is unit-tested in
 * [AppActiveNotificationPolicy].
 */
class AppActiveNotificationController(
    private val context: Context,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) = refresh()

    override fun onStop(owner: LifecycleOwner) = refresh()

    /** Posts or cancels the notice based on the current permission + live-share state. */
    private fun refresh() {
        val permitted = currentPushPermissionStatus(context) == PushPermissionStatus.GRANTED
        val liveShareActive = LocationSharingService.isSessionActive()
        if (AppActiveNotificationPolicy.shouldPost(permitted, liveShareActive)) post() else cancel()
    }

    private fun post() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun cancel() {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.appActive_notificationText))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent())
            // Ongoing so it reads as a status, not a dismissible alert; silent +
            // low priority + no badge so it never interrupts.
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /**
     * Tap → bring the EXISTING task forward rather than launch a duplicate
     * MainActivity. Same MAIN/LAUNCHER-with-NEW_TASK shape the launcher icon
     * sends (and the live-share notification uses): the platform resumes the
     * running task with its back stack intact. FLAG_IMMUTABLE per policy.
     */
    private fun openAppIntent(): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Idempotent: re-creating an existing channel id is a no-op for its
     * user-visible fields, so this is safe to call before every post. LOW
     * importance (no sound), no badge — deliberately unobtrusive. The member
     * opts out by disabling this channel in system settings; nothing else in the
     * app depends on it.
     */
    private fun ensureChannel(manager: NotificationManager) {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.appActive_notificationChannel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.appActive_notificationChannelDescription)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "app_active"
        // Distinct from LocationSharingService's 4201 so the two ongoing
        // notifications never collide.
        const val NOTIFICATION_ID = 4210
        const val REQUEST_OPEN_APP = 4211
    }
}
