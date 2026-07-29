package com.kungsbackacarcommunity.app.profile

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.kungsbackacarcommunity.app.firebase.await
import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    /**
     * Owns the profile reads, so an abandoned collector cannot throw away a read
     * that was already issued (see [readMissing]). [SupervisorJob] keeps one
     * failed batch from cancelling the others; the scope lives as long as the
     * process, like the cache it fills.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bounds how many profile queries are in flight at once (see [READ_CONCURRENCY]). */
    private val readSemaphore = Semaphore(READ_CONCURRENCY)

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

    /**
     * Every cached profile among [uids], INCLUDING expired ones
     * (stale-while-revalidate).
     *
     * Expiry decides whether to RE-READ a profile, never whether to show it. An
     * expired entry is a profile that was correct five minutes ago; the stored
     * denormalized copy it would otherwise fall back to is one that may be months
     * out of date. Dropping the expired entry would therefore make the whole list
     * revert to old names and avatars for one round-trip on the first update after
     * any idle window, then snap back — a full-list identity flicker, in exchange
     * for nothing.
     */
    private fun cachedProfiles(uids: Set<String>): Map<String, LiveProfile> {
        if (uids.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, LiveProfile>()
        for (uid in uids) {
            cache[uid]?.profile?.let { result[uid] = it }
        }
        return result
    }

    /**
     * Reads every uid that is not already cached-and-fresh, folds the results into
     * the cache, and returns the full picture for [uids].
     *
     * ## Why the reads run in a repository-owned scope
     *
     * The chat and inbox consumers call this from inside a `flatMapLatest`, which
     * CANCELS the previous inner flow on every new snapshot — i.e. on every
     * incoming message. A read launched in the caller's scope would then be
     * abandoned mid-flight, and because the cache is only written when a read
     * COMPLETES, nothing would be learned from it: the next message would start
     * the same read again. In a busy channel, where messages can arrive faster
     * than a round-trip completes, that never converges — the reads are issued
     * (and billed) forever and the avatars never actually refresh, in exactly the
     * channel where they are most visible.
     *
     * Launching in [scope] decouples the read's lifetime from the collector's: an
     * abandoned read still finishes and still populates the cache, so the next
     * emission is a hit and the overlay converges after ONE read cycle. The jobs
     * are bare Firestore queries writing into [cache] — no user state, nothing to
     * leak — and [READ_CONCURRENCY] bounds how many run at once.
     */
    private suspend fun readMissing(uids: Set<String>): Map<String, LiveProfile> {
        if (uids.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val missing = uids.filter { cache[it]?.isFresh(now) != true }
        if (missing.isEmpty()) return cachedProfiles(uids)

        missing
            .chunked(READ_CHUNK)
            .map { group -> scope.async { readSemaphore.withPermit { readChunk(group) } } }
            .awaitAll()
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

        // A uid the query did NOT return means "no such user" only if the query
        // actually reached the server. Offline, `get()` does not fail — it falls
        // back to Firestore's own local cache and SUCCEEDS with whatever is cached
        // there, often nothing at all. Recording those absences as deleted accounts
        // would poison this cache for a full TTL: every uid would read as
        // resolved-and-absent, no further read would be issued, and hydration would
        // go completely inert for five minutes after a moment of bad signal —
        // silently restoring the exact bug this class exists to fix.
        //
        // Documents that DID come back are kept either way: Firestore's local cache
        // holds real synced documents, so they are genuine profiles, merely not
        // confirmed this second.
        if (snapshot.metadata.isFromCache) return
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

        /**
         * Concurrent profile queries. A caller can legitimately ask for hundreds
         * of uids at once (the convoy list spans every convoy the member has ever
         * been in), and firing every chunk at once would put a burst of dozens of
         * simultaneous queries on a mobile connection for a purely cosmetic
         * overlay. Four keeps it prompt without monopolising the link.
         */
        private const val READ_CONCURRENCY = 4

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
         * Returns the SHARED live repository, or [LiveProfileRepository.EMPTY] when
         * Firebase is not configured. Never null: every consumer always has an
         * overlay to apply, and "no live opinion" is the safe config-less default.
         *
         * Named `sharedOrEmpty`, not `createOrEmpty`, precisely because it does NOT
         * behave like this codebase's other `create*` factories: those hand back a
         * fresh instance per call, whereas every caller here gets the SAME object,
         * and that is load-bearing rather than incidental — the cache and the read
         * scope are the whole point, and a per-consumer instance would silently
         * multiply the read cost by the number of open surfaces.
         */
        fun sharedOrEmpty(context: Context): LiveProfileRepository {
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
