package com.kungsbackacarcommunity.app.whatsnew

import org.json.JSONObject

/**
 * One released app version's notes from the bundled changelog
 * (`res/raw/changelog.json` — see that file's `note` field and
 * apps/android/README.md for the per-release maintenance contract).
 *
 * @property versionCode the Android `versionCode` of the release (monotonic).
 * @property versionName the user-visible version, e.g. `"0.4.0"`.
 * @property releaseDate ISO date (`yyyy-MM-dd`) — shown verbatim, which already
 *   matches the Swedish date convention.
 * @property highlights the few lines shown in the after-update popup.
 * @property changes the full bulleted list shown on the "Vad är nytt" page.
 */
data class ChangelogEntry(
    val versionCode: Int,
    val versionName: String,
    val releaseDate: String,
    val highlights: List<String>,
    val changes: List<String>,
)

/**
 * What the after-update popup should announce: the newest unseen entry's
 * highlights, plus whether earlier unseen versions were skipped over (→ the
 * popup appends an "…and more" hint instead of listing them all).
 */
data class UpdateAnnouncement(
    val entry: ChangelogEntry,
    val includesEarlierVersions: Boolean,
)

/**
 * Pure (Android-free) changelog logic: parsing the bundled JSON and deciding
 * what the "Vad är nytt" page and the after-update popup show. Kept free of
 * Context/IO so it is JVM-unit-testable (mirrors [ShellNavigation]).
 */
object Changelog {
    /** The "Vad är nytt" page shows at most this many releases, newest first. */
    const val PAGE_ENTRY_LIMIT = 10

    /** The popup shows at most this many highlight lines. */
    const val POPUP_HIGHLIGHT_LIMIT = 3

    /**
     * Parses the bundled changelog document (`{"note": …, "entries": [...]}`).
     * Entries missing any required field are skipped (a malformed release note
     * must never crash the app); the result is sorted newest-first and
     * de-duplicated by [ChangelogEntry.versionCode] (a versionCode maps to a
     * single release — accidental duplicates would double-count skipped
     * versions and render twice, so only the first per versionCode is kept).
     * Callers handle IO/JSON failure — see `ChangelogLoader`.
     */
    fun parse(json: String): List<ChangelogEntry> {
        val entries = JSONObject(json).optJSONArray("entries") ?: return emptyList()
        val parsed = mutableListOf<ChangelogEntry>()
        for (i in 0 until entries.length()) {
            val obj = entries.optJSONObject(i) ?: continue
            val versionCode = obj.optInt("versionCode", -1)
            val versionName = obj.optString("versionName", "")
            val releaseDate = obj.optString("releaseDate", "")
            if (versionCode <= 0 || versionName.isBlank() || releaseDate.isBlank()) continue
            parsed +=
                ChangelogEntry(
                    versionCode = versionCode,
                    versionName = versionName,
                    releaseDate = releaseDate,
                    highlights = stringList(obj, "highlights"),
                    changes = stringList(obj, "changes"),
                )
        }
        return parsed.sortedByDescending { it.versionCode }.distinctBy { it.versionCode }
    }

    private fun stringList(obj: JSONObject, key: String): List<String> {
        val array = obj.optJSONArray(key) ?: return emptyList()
        return (0 until array.length())
            .map { array.optString(it, "") }
            .filter { it.isNotBlank() }
    }

    /**
     * The releases the "Vad är nytt" page lists: the [limit] most recent
     * entries, newest first (tolerates unsorted input).
     */
    fun latestEntries(
        entries: List<ChangelogEntry>,
        limit: Int = PAGE_ENTRY_LIMIT,
    ): List<ChangelogEntry> = entries.sortedByDescending { it.versionCode }.take(limit)

    /**
     * Decides what the after-update popup announces, or null to stay silent.
     *
     * - First install ([lastSeenVersionCode] == null): silent — the caller
     *   records the current version instead (see `WhatsNewStore`).
     * - Not updated (lastSeen >= current): silent.
     * - Updated: the newest entry in `(lastSeen, current]` is announced;
     *   [UpdateAnnouncement.includesEarlierVersions] is true when more than one
     *   version was skipped so the popup can hint "…and more". Entries newer
     *   than [currentVersionCode] (notes accidentally shipped early) are never
     *   announced.
     */
    fun announcementFor(
        entries: List<ChangelogEntry>,
        lastSeenVersionCode: Int?,
        currentVersionCode: Int,
    ): UpdateAnnouncement? {
        if (lastSeenVersionCode == null) return null
        if (lastSeenVersionCode >= currentVersionCode) return null
        val unseen =
            entries
                .filter { it.versionCode > lastSeenVersionCode && it.versionCode <= currentVersionCode }
                .sortedByDescending { it.versionCode }
        val newest = unseen.firstOrNull() ?: return null
        return UpdateAnnouncement(
            entry = newest,
            includesEarlierVersions = unseen.size > 1,
        )
    }
}
