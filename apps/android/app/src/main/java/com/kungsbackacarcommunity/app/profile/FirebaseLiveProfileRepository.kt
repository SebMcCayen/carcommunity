package com.kungsbackacarcommunity.app.profile

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.kungsbackacarcommunity.app.firebase.await
import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [LiveProfileRepository] backed by batched one-shot `users/{uid}` reads behind
 * a short-lived in-memory cache.
 *
 * ## Reads, not listeners
 *
 * A snapshot listener per member would keep every rendered avatar live to the
 * second, at the cost of one open listener per distinct uid on screen — up to
 * [com.kungsbackacarcommunity.app.dm.DM_CONVERSATIONS_QUERY_LIMIT] on the inbox,
 * and one per distinct chat sender in the window. The bug being fixed is a copy
 * frozen for DAYS, not one stale for a minute, so one-shot reads behind a
 * [CACHE_TTL_MILLIS] cache buy nearly all of the correctness for a bounded and
 * predictable number of reads.
 *
 * ## Read cost
 *
 * At most ONE document read per distinct member per [CACHE_TTL_MILLIS] window,
 * shared across every surface because the cache lives on the single repository
 * instance. Reads are batched — one query per [READ_CHUNK] uids, mirroring
 * `PROFILE_READ_CHUNK` in `functions/src/friends/manageFriends.ts` — never one
 * query per uid. Callers de-duplicate by uid before calling
 * ([LiveProfiles.uidsOf]), so a 500-message chat window costs a read per distinct
 * SENDER, not per message.
 *
 * Two screens opening at once may each read the same uncached uid: in-flight
 * reads are not de-duplicated, because the coordination needed to do that costs
 * more than the occasional duplicated single-document read it would save.
 *
 * ## Never fails
 *
 * A failed read leaves the uid ABSENT from the result and is NOT cached, so the
 * caller falls back to its stored copy and the next batch retries. This is the
 * same best-effort contract the server-side friends hydration uses: a stale
 * avatar beats an empty screen.
 */
class FirebaseLiveProfileRepository private constructor(
    private val firestore: FirebaseFirestore,
) : LiveProfileRepository {

    /**
     * uid → last successful read. A [CacheEntry] with a null [CacheEntry.profile]
     * records a member whose document does NOT exist (a deleted account), which is
     * worth caching so a deleted counterparty is not re-read on every emission —
     * but is filtered out of the returned map, because absent is what tells the
     * caller to keep its stored copy ([LiveProfiles.resolve]).
     */
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(val profile: LiveProfile?, val readAtMillis: Long)

    override fun observeProfiles(uids: Set<String>): Flow<Map<String, LiveProfile>> = flow {
        // Emit what is already known FIRST, before any network work. Consumers
        // fold this into a message/row flow, so this first emission is what keeps
        // a screen from waiting on the network to render rows it already has.
        val cached = cachedProfiles(uids)
        emit(cached)

        val refreshed = readMissing(uids)
        // Only emit again when the reads actually changed something; an unchanged
        // map would churn every downstream `flatMapLatest` for nothing.
        if (refreshed != cached) emit(refreshed)
    }

    override suspend fun loadProfiles(uids: Set<String>): Map<String, LiveProfile> =
        readMissing(uids)

    /** The subset of [uids] with an unexpired cached profile. */
    private fun cachedProfiles(uids: Set<String>): Map<String, LiveProfile> {
        if (uids.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, LiveProfile>()
        for (uid in uids) {
            val entry = cache[uid] ?: continue
            if (!entry.isFresh(now)) continue
            entry.profile?.let { result[uid] = it }
        }
        return result
    }

    /**
     * Reads every uid that is not already cached-and-fresh, folds the results into
     * the cache, and returns the full picture for [uids].
     */
    private suspend fun readMissing(uids: Set<String>): Map<String, LiveProfile> {
        if (uids.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val missing = uids.filter { cache[it]?.isFresh(now) != true }
        if (missing.isEmpty()) return cachedProfiles(uids)

        coroutineScope {
            missing.chunked(READ_CHUNK).map { group -> async { readChunk(group) } }.awaitAll()
        }
        return cachedProfiles(uids)
    }

    /**
     * Reads one batch of up to [READ_CHUNK] user documents and caches the outcome.
     *
     * Results are keyed by `document.id`, never by position: a `whereIn` query
     * returns only the documents that EXIST, in no guaranteed order, so pairing by
     * index would hand one member's name and picture to another. The uids the
     * query did not return are exactly the non-existent ones, cached as a null
     * profile.
     */
    private suspend fun readChunk(uids: List<String>) {
        val snapshot =
            runCatchingCancellable {
                firestore
                    .collection(USERS)
                    .whereIn(FieldPath.documentId(), uids)
                    .get()
                    .await()
            }
                // Best-effort: leave the whole batch uncached so these uids fall
                // back to their stored copies and the next batch retries them.
                .getOrNull() ?: return

        val readAtMillis = System.currentTimeMillis()
        val found = mutableSetOf<String>()
        for (document in snapshot.documents) {
            found += document.id
            cache[document.id] =
                CacheEntry(
                    LiveProfile(
                        displayName = document.getString(DISPLAY_NAME),
                        avatarPath = document.getString(AVATAR_PATH),
                    ),
                    readAtMillis,
                )
        }
        for (uid in uids) {
            if (uid !in found) cache[uid] = CacheEntry(null, readAtMillis)
        }
    }

    private fun CacheEntry.isFresh(nowMillis: Long): Boolean =
        nowMillis - readAtMillis < CACHE_TTL_MILLIS

    companion object {
        /**
         * How long a read profile is reused before being read again. Long enough
         * that scrolling a chat or reopening the inbox costs nothing, short enough
         * that a member's new avatar appears within the same session.
         */
        private const val CACHE_TTL_MILLIS = 5 * 60 * 1000L

        /** Firestore caps an `in` filter at 30 values. */
        private const val READ_CHUNK = 30

        private const val USERS = "users"
        private const val DISPLAY_NAME = "displayName"
        private const val AVATAR_PATH = "avatarPath"

        /**
         * The single process-wide instance, so ALL surfaces share ONE cache.
         *
         * This is what makes the read cost stated above true: the DM inbox, both
         * chat channels and the convoy roster overwhelmingly name the SAME people
         * (your friends and convoy mates), and a repository-per-consumer would
         * re-read every one of them once per surface. It is a bare cache behind a
         * read-only Firestore query, holding no per-user state and no listener, so
         * there is nothing here to leak or to reset on sign-out — a signed-out
         * client simply stops asking.
         */
        @Volatile private var instance: FirebaseLiveProfileRepository? = null

        /**
         * Returns the shared live repository, or [LiveProfileRepository.EMPTY] when
         * Firebase is not configured. Never null: every consumer always has an
         * overlay to apply, and "no live opinion" is the safe config-less default.
         */
        fun createOrEmpty(context: Context): LiveProfileRepository {
            if (FirebaseApp.getApps(context).isEmpty()) return LiveProfileRepository.EMPTY
            return instance
                ?: synchronized(this) {
                    instance
                        ?: FirebaseLiveProfileRepository(FirebaseFirestore.getInstance())
                            .also { instance = it }
                }
        }
    }
}
