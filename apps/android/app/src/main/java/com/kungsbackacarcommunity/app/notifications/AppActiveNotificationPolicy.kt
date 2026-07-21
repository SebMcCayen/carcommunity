package com.kungsbackacarcommunity.app.notifications

/**
 * Pure decision for the "app is active" ongoing notification — the small,
 * ordinary notice shown while the app process is alive so a member who leaves
 * the app can still see in the shade that it is running (Seb's request:
 * "if you leave the app you still see that it is active").
 *
 * Kept free of Android types so the whole decision is unit-tested; the platform
 * posting/cancelling lives in [AppActiveNotificationController], which is
 * instrumentation-only (no device in CI).
 */
object AppActiveNotificationPolicy {

    /**
     * Whether to POST the notice (true) or CANCEL it (false).
     *
     * Two — and only two — reasons suppress it:
     *
     * - **Notifications not permitted.** On Android 13+ POST_NOTIFICATIONS is a
     *   runtime grant; without it `notify()` is a silent no-op anyway. We do not
     *   post and — deliberately — do not prompt: the app already asks for
     *   POST_NOTIFICATIONS at the natural moments (notification settings, chat,
     *   the authed shell). A status notice must never trigger a second ask.
     *
     * - **A live-location session is running.** That session (its foreground
     *   service, added in #495) already owns an ongoing notification in the
     *   shade. Showing a second "app is active" notice next to it is just noise,
     *   so the status notice yields while sharing is up and reappears — via the
     *   next foreground/background re-evaluation — once the shade is free.
     */
    fun shouldPost(notificationsPermitted: Boolean, liveShareActive: Boolean): Boolean =
        notificationsPermitted && !liveShareActive
}
