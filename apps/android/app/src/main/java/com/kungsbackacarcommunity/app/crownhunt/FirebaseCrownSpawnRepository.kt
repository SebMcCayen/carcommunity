package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import java.time.Instant

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
     * One query per refresh, shaped to the deployed composite index
     * (`cellKey ASC, status ASC, expiresAt ASC`).
     *
     * All three conditions are on the query, not applied afterwards, because the
     * security rule checks the QUERY: `status == 'live'` and `expiresAt > now`
     * are what make the read legal at all, and dropping either turns the whole
     * call into a permission denial rather than a wider result set.
     */
    override suspend fun listNearby(cellKeys: List<String>, nowMillis: Long): List<CrownSpawn> {
        // Firestore rejects an empty `in` array outright, and an empty plan has
        // nothing to ask for — so this is a local no-op, not a failed round-trip.
        if (cellKeys.isEmpty()) return emptyList()
        val now = Timestamp(Instant.ofEpochMilli(nowMillis))
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

    companion object {
        private const val REGION = "europe-west1"
        private const val SPAWNS = "crownSpawns"
        private const val CLAIM_SPAWN = "crownHunt-claimSpawn"
        private const val FIELD_CELL_KEY = "cellKey"
        private const val FIELD_STATUS = "status"
        private const val FIELD_EXPIRES_AT = "expiresAt"
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
        collectRadiusMeters =
            getDouble("collectRadiusMeters") ?: CrownSpawnLimits.COLLECT_RADIUS_METERS,
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
