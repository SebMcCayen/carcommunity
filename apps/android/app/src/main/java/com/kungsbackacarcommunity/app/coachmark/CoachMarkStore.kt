package com.kungsbackacarcommunity.app.coachmark

import android.content.Context

/**
 * Remembers whether a user has already seen the one-time first-login coach-mark
 * tour, so it shows exactly once per user on this device and never re-appears —
 * not on the next launch, and not on re-login.
 *
 * Device-local by design (SharedPreferences, no Firebase): "has this user seen
 * the map-home tips on this device" is not account state worth syncing to
 * Firestore, and persisting it locally avoids a backend/rules change. This is a
 * direct copy of the [com.kungsbackacarcommunity.app.welcome.WelcomeStore]
 * pattern — the sibling first-login flag — kept in its own preferences file so
 * the two flags stay independent.
 *
 * Keyed by uid: the flag is stored under a per-user key so switching accounts on
 * the same device shows each new user their own tour once. A fresh user (no key
 * yet) has [hasSeenCoachMarks] == false and is shown the tour; any dismissal
 * path (Skip, or completing the last tip) calls [markSeen].
 */
class CoachMarkStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether [uid] has already completed/skipped the coach-mark tour on this device. */
    fun hasSeenCoachMarks(uid: String): Boolean = prefs.getBoolean(key(uid), false)

    /** Records that [uid] has seen the tour (any dismissal path: Skip or Done). */
    fun markSeen(uid: String) {
        prefs.edit().putBoolean(key(uid), true).apply()
    }

    private fun key(uid: String): String = "$KEY_SEEN_PREFIX$uid"

    private companion object {
        const val PREFS_NAME = "first_login_coach_marks"
        const val KEY_SEEN_PREFIX = "seen_"
    }
}
