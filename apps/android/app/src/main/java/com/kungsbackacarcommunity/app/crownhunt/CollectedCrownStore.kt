package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import org.json.JSONObject

/**
 * Durable, per-user record of the SHARED crowns THIS member has already collected
 * but which stay `live` on the map for others — the crowns
 * [CrownSpawnController] draws with the distinct "collected by you" marker.
 *
 * ## Why this has to survive process death
 *
 * The controller holds the collected-set in memory (see
 * [CrownSpawnController.collectedSpawnIds]). That is enough while the process
 * lives, but a shared crown the member picked up hours ago is STILL returned by
 * `listNearby` (it is live for others), so on a cold start with an empty
 * in-memory set the crown re-appears looking collectable. The member taps it, the
 * server refuses with `ALREADY_COLLECTED`, and only THEN does the mark come back —
 * a tap the user should never have had to make. Persisting the set locally makes
 * the mark reappear immediately on reopen, no tap needed (owner bug: "collected
 * mark disappears after closing/reopening the app").
 *
 * ## UID-scoped, on purpose (privacy)
 *
 * The set is keyed by uid — account A's collected marks must never show for
 * account B on a shared device. A fresh uid (no stored blob) starts empty. This
 * mirrors [com.kungsbackacarcommunity.app.coachmark.CoachMarkStore]'s per-user
 * keying.
 *
 * The [PrefsCollectedCrownStore] backing is device-local SharedPreferences — the
 * same lightweight persistence the sibling stores use — and NOT synced to
 * Firestore: "which live shared crowns has this member already picked up on this
 * device" is cheap-to-relearn visual state (one tap), not account state worth a
 * backend/rules change. A reinstall or a new device therefore starts empty and
 * re-learns on first tap, exactly as today; the fully-authoritative alternative
 * (a server-derived collected-by-me per visible spawn) is noted as a possible
 * follow-up on the PR.
 */
interface CollectedCrownStore {
    /**
     * The persisted (spawn id -> crown expiry) set for [uid], or empty when the
     * user has none stored. Expiry is nullable when the crown document omitted
     * one. Never throws: a corrupt payload degrades to empty.
     */
    fun load(uid: String): Map<String, Long?>

    /** Replaces the whole stored set for [uid]. Never throws. */
    fun save(uid: String, entries: Map<String, Long?>)
}

/**
 * A store that keeps nothing — the default the pure controller uses in tests and
 * the fallback when no [Context] is available, so persistence is strictly an
 * additive behaviour on top of the existing in-memory path.
 */
object NoOpCollectedCrownStore : CollectedCrownStore {
    override fun load(uid: String): Map<String, Long?> = emptyMap()

    override fun save(uid: String, entries: Map<String, Long?>) = Unit
}

/**
 * [CollectedCrownStore] backed by [android.content.SharedPreferences], mirroring
 * the app's other small local stores (e.g.
 * [com.kungsbackacarcommunity.app.navigation.PrefsRecentSearchesStore]).
 *
 * Each user's set is stored as a compact JSON object under a per-uid key, so
 * switching accounts on the same device reads a different blob. Every access is
 * wrapped defensively: an absent or unparseable payload degrades to empty rather
 * than crashing the crown layer. The [prefs] handle is lazy so the file's first
 * disk read happens on whichever (background) thread first calls [load], not at
 * construction time on the composition thread.
 */
class PrefsCollectedCrownStore(context: Context) : CollectedCrownStore {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun load(uid: String): Map<String, Long?> =
        runCatching { decode(prefs.getString(key(uid), null)) }.getOrDefault(emptyMap())

    override fun save(uid: String, entries: Map<String, Long?>) {
        // apply(), not commit(): losing a write only costs one avoidable
        // ALREADY_COLLECTED re-tap after an abrupt kill, never data or money, so
        // the async flush is the right trade and keeps the caller off disk I/O.
        runCatching {
            if (entries.isEmpty()) {
                prefs.edit().remove(key(uid)).apply()
            } else {
                prefs.edit().putString(key(uid), encode(entries)).apply()
            }
        }
    }

    private fun key(uid: String): String = "$KEY_PREFIX$uid"

    /**
     * `internal` rather than private so the pure (de)serialization can be
     * unit-tested on the JVM: the store itself needs a [Context] and so cannot be
     * constructed in a plain unit test, but [encode]/[decode] round-trip the
     * id -> expiry map and disarm a corrupt payload, and are worth pinning
     * directly (see `CollectedCrownStoreTest`).
     */
    internal companion object {
        const val PREFS_NAME = "crownhunt_collected_spawns"
        const val KEY_PREFIX = "user_"

        /** Sentinel for "the crown document omitted an expiry" (a null value). */
        private const val NO_EXPIRY = -1L

        fun encode(entries: Map<String, Long?>): String {
            val obj = JSONObject()
            for ((id, expiry) in entries) {
                if (id.isBlank()) continue
                // Store null expiry as a sentinel rather than JSONObject.NULL so a
                // decode never has to distinguish "missing key" from "null value".
                obj.put(id, expiry ?: NO_EXPIRY)
            }
            return obj.toString()
        }

        fun decode(raw: String?): Map<String, Long?> {
            if (raw.isNullOrBlank()) return emptyMap()
            val obj = JSONObject(raw)
            val out = LinkedHashMap<String, Long?>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                if (id.isBlank()) continue
                // optLong defaults a non-numeric/corrupt value to NO_EXPIRY, which
                // simply reads back as "unknown expiry" — safe, never a crash.
                val stored = obj.optLong(id, NO_EXPIRY)
                out[id] = if (stored == NO_EXPIRY) null else stored
            }
            return out
        }
    }
}
