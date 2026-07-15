package com.kungsbackacarcommunity.app.welcome

import android.content.Context

/**
 * Remembers whether a user has already seen the one-time first-login welcome
 * flow, so it shows exactly once per user on this device.
 *
 * Device-local by design (SharedPreferences, no Firebase): "has this user seen
 * the intro on this device" is not account state worth syncing to Firestore,
 * and persisting it locally avoids a backend/rules change. Mirrors the existing
 * [com.kungsbackacarcommunity.app.whatsnew.WhatsNewStore] pattern.
 *
 * Keyed by uid: the flag is stored under a per-user key so switching accounts on
 * the same device shows each new user their own welcome once. A fresh user (no
 * key yet) has [hasSeenWelcome] == false and is shown the flow; any dismissal
 * path (skip, "Get started", or a CTA that navigates away) calls [markSeen].
 */
class WelcomeStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether [uid] has already completed/dismissed the welcome flow on this device. */
    fun hasSeenWelcome(uid: String): Boolean = prefs.getBoolean(key(uid), false)

    /** Records that [uid] has seen the welcome flow (any dismissal path). */
    fun markSeen(uid: String) {
        prefs.edit().putBoolean(key(uid), true).apply()
    }

    private fun key(uid: String): String = "$KEY_SEEN_PREFIX$uid"

    private companion object {
        const val PREFS_NAME = "first_login_welcome"
        const val KEY_SEEN_PREFIX = "seen_"
    }
}
