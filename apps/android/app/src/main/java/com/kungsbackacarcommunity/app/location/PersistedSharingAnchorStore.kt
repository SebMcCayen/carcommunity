package com.kungsbackacarcommunity.app.location

import android.content.Context

/**
 * [SharingAnchorStore] backed by SharedPreferences, so the hard-ceiling anchor
 * survives process death and `START_REDELIVER_INTENT` restarts.
 *
 * Only a session id and a timestamp are stored — no position, no uid. The id is
 * server-generated and meaningless outside an active session, and it is cleared
 * as soon as sharing ends.
 */
class PersistedSharingAnchorStore(context: Context) : SharingAnchorStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun anchorFor(sessionId: String, nowMillis: Long): Long {
        val storedId = prefs.getString(KEY_SESSION_ID, null)
        if (storedId == sessionId) {
            val stored = prefs.getLong(KEY_ANCHOR_MILLIS, NO_ANCHOR)
            if (stored != NO_ANCHOR) return stored
        }
        // A different (or first) session, or a half-written pair: start fresh.
        //
        // commit(), not apply(): apply() only guarantees the in-memory value and
        // flushes to disk asynchronously, and while the platform drains pending
        // apply() writes on orderly component transitions it cannot on an abrupt
        // SIGKILL — which is exactly the scenario this anchor defends against.
        // Losing the write there would reset the ceiling on restart. The cost is
        // acceptable: this writes once per session, and every caller reaches it
        // from the service's Dispatchers.IO scope, never the main thread.
        prefs.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putLong(KEY_ANCHOR_MILLIS, nowMillis)
            .commit()
        return nowMillis
    }

    /** apply() is fine here — losing a clear only costs one stale, keyed entry. */
    override fun clear() {
        prefs.edit().remove(KEY_SESSION_ID).remove(KEY_ANCHOR_MILLIS).apply()
    }

    private companion object {
        const val PREFS_NAME = "live_sharing_anchor"
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_ANCHOR_MILLIS = "anchorMillis"
        const val NO_ANCHOR = -1L
    }
}
