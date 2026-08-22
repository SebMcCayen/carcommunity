package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [CrownSpawnRepository] backed by a bounded Firestore query on `crownSpawns`
 * plus the `crownHunt-claimSpawn` callable (europe-west1).
 *
 * Construction is guarded — [createIfAvailable] returns null without Firebase,
 * so a config-less build (CI, previews) simply has no crown layer rather than
 * crashing on first draw.
 */
class FirebaseCrownSpawnRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : CrownSpawnRepository {

    /**
     * One or more parallel queries per refresh, each shaped to the deployed
     * composite index (`cellKey ASC, status ASC, expiresAt ASC`).
     *
     * A town-sized plan needs more cells than an `in` filter's 30-value limit, so
     * the plan is split into batches of at most [CrownSpawnQuery.FIRESTORE_IN_LIMIT]
     * keys ([CrownSpawnQuery.chunkForInQueries]) and each batch is its OWN `in`
     * query. The batches run in parallel and their results are merged, deduped by
     * crown id (a crown's cell is unique so overlap is not expected, but the map
     * de-dupes rather than trusting that). Each batch is an independent `in`
     * query, so this uses the SAME composite index — no new index is needed.
     *
     * All three conditions are on every query, not applied afterwards, because the
     * security rule checks the QUERY: `status == 'live'` and `expiresAt > now`
     * are what make the read legal at all, and dropping either turns the whole
     * call into a permission denial rather than a wider result set.
     */
    override suspend fun listNearby(cellKeys: List<String>, nowMillis: Long): List<CrownSpawn> {
        // Firestore rejects an empty `in` array outright, and an empty plan has
        // nothing to ask for — so this is a local no-op, not a failed round-trip.
        if (cellKeys.isEmpty()) return emptyList()
        val now = Timestamp(Instant.ofEpochMilli(nowMillis))
        val batches = CrownSpawnQuery.chunkForInQueries(cellKeys)
        val perBatch =
            coroutineScope {
                batches
                    .map { batch -> async { queryBatch(batch, now) } }
                    .awaitAll()
            }
        // Merge, deduping by crown id and preserving first-seen order, then apply
        // the same overall draw cap a single query used to enforce — a widened
        // plan must not turn one pan into an unbounded annotation redraw.
        val merged = LinkedHashMap<String, CrownSpawn>()
        for (spawns in perBatch) {
            for (spawn in spawns) merged.putIfAbsent(spawn.id, spawn)
        }
        return merged.values.take(CrownSpawnRepository.MAX_SPAWNS_PER_QUERY.toInt())
    }

    /** One batch = one legal `in` query on the shared composite index. */
    private suspend fun queryBatch(cellKeys: List<String>, now: Timestamp): List<CrownSpawn> {
        val snapshot =
            firestore
                .collection(SPAWNS)
                .whereIn(FIELD_CELL_KEY, cellKeys)
                .whereEqualTo(FIELD_STATUS, STATUS_LIVE)
                .whereGreaterThan(FIELD_EXPIRES_AT, now)
                .limit(CrownSpawnRepository.MAX_SPAWNS_PER_QUERY)
                .get()
                .awaitOrThrow { "crownSpawns query failed without a cause" }
        return snapshot.documents.mapNotNull { it.toSpawn() }
    }

    override suspend fun claimSpawn(
        spawnId: String,
        current: CrownFix,
        previous: CrownFix,
        idempotencyKey: String,
    ): CrownSpawnClaimOutcome {
        val payload =
            buildMap<String, Any> {
                put("spawnId", spawnId)
                put("latitude", current.latitude)
                put("longitude", current.longitude)
                put("recordedAt", isoOf(current.recordedAtMillis))
                put("previousFix", previous.toWire())
                put("idempotencyKey", idempotencyKey)
                current.accuracyMeters?.let { put("accuracyMeters", it) }
                current.speedMetersPerSecond?.let { put("speedMetersPerSecond", it) }
                // Sent as reported, never suppressed. The backend treats this as
                // a one-way signal (true is penalised, false and absent are the
                // same), so honesty costs an honest client nothing.
                current.isMock?.let { put("isMockLocation", it) }
            }
        val response =
            functions
                .getHttpsCallable(CLAIM_SPAWN)
                .call(payload)
                .awaitOrThrow { "claimSpawn failed without a cause" }
        // HttpsCallableResult exposes getData(); the `data` field itself is
        // private, so call the accessor.
        @Suppress("UNCHECKED_CAST")
        val data = response?.getData() as? Map<String, Any?>
        return data?.toClaimOutcome()
            ?: throw IllegalStateException("claimSpawn returned an unrecognized result")
    }

    /**
     * The SERVER-AUTHORITATIVE collected-set: a live listener on the member's OWN
     * `crownSpawnCollectors` records (`userId == uid`), emitting the current
     * (spawn id -> crown expiry millis) map on every snapshot.
     *
     * The query is filtered to `userId == uid`, which is exactly what the
     * owner-only read rule requires (an unfiltered list would be DENIED), and
     * needs only the automatic single-field index on `userId`. Expired records are
     * NOT filtered here — the collection self-reaps via its `expireAt` TTL policy
     * and the controller prunes by expiry anyway, so a userId-only query avoids a
     * composite index. A `null` value means the document omitted `expireAt`.
     *
     * On a listener error the snapshot is simply skipped (never emits an empty map
     * on a transient permission/transport blip), so the local cache stays the
     * fallback rather than being wrongly cleared.
     */
    override fun observeCollected(uid: String): Flow<Map<String, Long?>> =
        callbackFlow {
            val registration =
                firestore
                    .collection(COLLECTORS)
                    .whereEqualTo(FIELD_USER_ID, uid)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null || snapshot == null) return@addSnapshotListener
                        val collected = LinkedHashMap<String, Long?>(snapshot.size())
                        for (doc in snapshot.documents) {
                            val spawnId = doc.getString(FIELD_SPAWN_ID) ?: continue
                            collected[spawnId] = doc.getTimestamp(FIELD_EXPIRE_AT)?.toDate()?.time
                        }
                        trySend(collected)
                    }
            awaitClose { registration.remove() }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val SPAWNS = "crownSpawns"
        private const val COLLECTORS = "crownSpawnCollectors"
        private const val CLAIM_SPAWN = "crownHunt-claimSpawn"
        private const val FIELD_CELL_KEY = "cellKey"
        private const val FIELD_STATUS = "status"
        private const val FIELD_EXPIRES_AT = "expiresAt"
        private const val FIELD_USER_ID = "userId"
        private const val FIELD_SPAWN_ID = "spawnId"
        private const val FIELD_EXPIRE_AT = "expireAt"
        private const val STATUS_LIVE = "live"

        fun createIfAvailable(context: Context): CrownSpawnRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseCrownSpawnRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

private fun isoOf(millis: Long): String = Instant.ofEpochMilli(millis).toString()

private fun CrownFix.toWire(): Map<String, Any> =
    buildMap {
        put("latitude", latitude)
        put("longitude", longitude)
        put("recordedAt", isoOf(recordedAtMillis))
        accuracyMeters?.let { put("accuracyMeters", it) }
        speedMetersPerSecond?.let { put("speedMetersPerSecond", it) }
    }

/**
 * Parses one `crownSpawns` document.
 *
 * A document missing a coordinate or an unrecognised rarity is SKIPPED rather
 * than defaulted: a crown drawn at (0,0), or one shown as "common, 10 KP" when
 * the server thinks it is legendary, is worse than a crown that is briefly
 * absent — and schema drift stays visible instead of being papered over.
 */
private fun DocumentSnapshot.toSpawn(): CrownSpawn? {
    if (!exists()) return null
    val latitude = getDouble("latitude") ?: return null
    val longitude = getDouble("longitude") ?: return null
    val rarity = CrownRarity.fromWire(getString("rarity")) ?: return null
    return CrownSpawn(
        id = id,
        latitude = latitude,
        longitude = longitude,
        rarity = rarity,
        // The server is authoritative about what THIS crown pays; the tier's
        // table is only the fallback for a document that omits the field, so a
        // retuned reward shows up without an app release.
        rewardPoints = (get("rewardPoints") as? Number)?.toInt() ?: rarity.rewardPoints,
        // Sanitized here, at the ONE place a document becomes a CrownSpawn, so
        // the popup's "get within X m" and the Collect gate can never disagree
        // about a crown. A missing, zero, negative, non-finite or absurd radius
        // all become the mirrored 75 m — the same answer the backend's
        // `resolveCollectRadiusMeters` would give when it re-checks the claim.
        collectRadiusMeters =
            CrownSpawnLimits.resolveCollectRadiusMeters(getDouble("collectRadiusMeters")),
        expiresAtMillis = getTimestamp("expiresAt")?.toDate()?.time,
    )
}

private fun Map<String, Any?>.toClaimOutcome(): CrownSpawnClaimOutcome? {
    val result = CrownSpawnClaimResult.fromWire(this["result"] as? String) ?: return null
    return CrownSpawnClaimOutcome(
        result = result,
        pointsAwarded = (this["pointsAwarded"] as? Number)?.toInt(),
        newBalance = (this["newBalance"] as? Number)?.toInt(),
        rarity = CrownRarity.fromWire(this["rarity"] as? String),
    )
}
