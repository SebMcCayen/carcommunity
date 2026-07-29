package com.kungsbackacarcommunity.app.update

import android.content.Context

/**
 * Persists the last "Inte nu" tap so the update prompt cannot reappear on
 * the next screen or the next cold start.
 *
 * Device-local by design (SharedPreferences, like WhatsNewStore):
 * whether a popup was waved away on this phone is not account state worth
 * syncing, and a Firestore write here would make dismissal depend on the
 * network.
 *
 * Both fields are written together and read together — a record with only
 * one of them is treated as no record at all, so a half-written state can
 * only ever cause one extra prompt, never a permanent silence.
 */
class AppUpdateDismissalStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The last recorded dismissal, or null when there is none. */
    fun read(): AppUpdateDismissal? {
        if (!prefs.contains(KEY_VERSION_CODE) || !prefs.contains(KEY_AT_MILLIS)) return null
        return AppUpdateDismissal(
            versionCode = prefs.getInt(KEY_VERSION_CODE, 0),
            atMillis = prefs.getLong(KEY_AT_MILLIS, 0L),
        )
    }

    /** Records that the prompt for [versionCode] was dismissed at [atMillis]. */
    fun record(versionCode: Int, atMillis: Long) {
        prefs
            .edit()
            .putInt(KEY_VERSION_CODE, versionCode)
            .putLong(KEY_AT_MILLIS, atMillis)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "app_update_prompt"
        const val KEY_VERSION_CODE = "dismissedVersionCode"
        const val KEY_AT_MILLIS = "dismissedAtMillis"
    }
}
