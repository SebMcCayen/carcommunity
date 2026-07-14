package com.kungsbackacarcommunity.app.whatsnew

import android.content.Context
import android.util.Log
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable

/**
 * Loads the bundled changelog from `res/raw/changelog.json`. Failures (missing
 * resource, malformed JSON) degrade to an empty list — the popup then never
 * shows and the "Vad är nytt" page renders its empty state, but the app keeps
 * working.
 */
object ChangelogLoader {
    private const val TAG = "ChangelogLoader"

    fun load(context: Context): List<ChangelogEntry> =
        runCatchingCancellable {
            context.resources
                .openRawResource(R.raw.changelog)
                .bufferedReader()
                .use { it.readText() }
                .let(Changelog::parse)
        }.getOrElse { e ->
            // Cancellation is re-thrown by runCatchingCancellable (structured
            // concurrency); only real failures reach here and degrade to none.
            Log.w(TAG, "Could not load the bundled changelog; showing none.", e)
            emptyList()
        }
}

/**
 * Persists the last app version (versionCode) whose "what's new" the user has
 * seen, so the after-update popup shows at most once per version.
 *
 * Device-local by design (SharedPreferences, no Firebase): whether an update
 * popup was dismissed is not account state worth syncing. There is no existing
 * local-persistence helper in the app to reuse — everything else is Firestore-
 * backed — so this is deliberately the first and only SharedPreferences use.
 *
 * First install: no value is stored yet ([lastSeenVersionCode] == null); the
 * caller records the current version WITHOUT showing the popup (updates only).
 */
class WhatsNewStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The last versionCode acknowledged, or null when never recorded (first install). */
    fun lastSeenVersionCode(): Int? =
        if (prefs.contains(KEY_LAST_SEEN)) prefs.getInt(KEY_LAST_SEEN, 0) else null

    /** Records [versionCode] as seen (popup dismissed, or first-install baseline). */
    fun markSeen(versionCode: Int) {
        prefs.edit().putInt(KEY_LAST_SEEN, versionCode).apply()
    }

    private companion object {
        const val PREFS_NAME = "whats_new"
        const val KEY_LAST_SEEN = "lastSeenVersionCode"
    }
}
