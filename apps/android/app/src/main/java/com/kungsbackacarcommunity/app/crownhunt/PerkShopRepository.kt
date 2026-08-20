package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import java.util.Date
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Outcome of a successful `crownHunt-buyPerk` call. */
data class PerkPurchaseResult(
    val perkId: String,
    val qty: Long,
    /** KP actually spent (server-authoritative cost * qty). */
    val costKp: Long,
    /** KP balance after the debit. */
    val newBalance: Long,
    /** The buyer's count of this perk AFTER the grant. */
    val inventoryCount: Long,
    /** True when an idempotent replay returned the original purchase. */
    val alreadyPurchased: Boolean,
)

/** Outcome of a successful `crownHunt-deployPerk` call. */
data class PerkDeployResult(
    val perkId: String,
    /** Which effect was applied (trap / shield / boost). */
    val kind: PerkKind,
    /** The trap/shield/boost effect id (trap doc id, or the holder uid). */
    val effectId: String,
    /** Epoch-ms the deployed effect expires. */
    val expiresAtMillis: Long,
    /** Remaining owned count of this perk after the consume. */
    val inventoryCount: Long,
    /** True when an idempotent replay returned the original deploy. */
    val alreadyDeployed: Boolean,
)

/**
 * Thrown when `deployPerk` refused for a non-location reason: the
 * `crownHuntPerks` flag is off, the perk is unknown, the member owns none, or a
 * trap anti-abuse cap (1 active / 3-per-day / 300 m spacing) was hit. All of
 * these arrive as the server's `failed-precondition`; the deploy callable
 * attaches no structured `details.reason`, so they collapse to one family.
 */
class PerkDeployUnavailableException(cause: Throwable? = null) :
    Exception("deployPerk rejected: unavailable.", cause)

/**
 * Thrown when the server rejected a trap because it carried no/invalid current
 * position (`invalid-argument`). The client also pre-checks the GPS fix, so this
 * is the belt-and-braces server side of the same "we need your location" case.
 */
class PerkDeployMissingLocationException(cause: Throwable? = null) :
    Exception("deployPerk rejected: a trap needs a current position.", cause)

/**
 * Thrown when the buy was rejected because the member cannot afford the perk —
 * the server's ledger debit hit the `failed-precondition` overdraft guard, which
 * buyPerk re-throws with `details.reason == "insufficient_funds"`. Distinct from
 * [PerkPurchaseUnavailableException] so the UI shows a "not enough KP" hint
 * rather than a generic error.
 */
class PerkPurchaseInsufficientFundsException(cause: Throwable? = null) :
    Exception("buyPerk rejected: insufficient Kronpoäng.", cause)

/**
 * Thrown when the shop itself refused the buy for a non-affordability reason:
 * the `crownHuntPerks` flag is off, the account cannot spend, or the perk id is
 * unknown. All three share the server's `failed-precondition` code and carry a
 * `details.reason` other than `insufficient_funds` (e.g. `shop_unavailable`), so
 * the UI treats them as a single "shop unavailable" message.
 */
class PerkPurchaseUnavailableException(cause: Throwable? = null) :
    Exception("buyPerk rejected: shop unavailable.", cause)

/**
 * Thrown when the buy was refused because the member already holds the maximum of
 * this perk (per-perk count cap) or their total banked perk value is at the
 * ceiling — server `details.reason == "hold_cap_reached"`. Distinct so the UI can
 * say "use some first" rather than a generic error.
 */
class PerkPurchaseHoldCapException(cause: Throwable? = null) :
    Exception("buyPerk rejected: hold cap reached.", cause)

/**
 * Thrown when the buy was refused because the member bought too recently — server
 * `details.reason == "purchase_cooldown"`. Distinct so the UI can say "wait a
 * moment" rather than a generic error.
 */
class PerkPurchaseCooldownException(cause: Throwable? = null) :
    Exception("buyPerk rejected: purchase cooldown.", cause)

/**
 * Thrown when a deploy was refused because it would exceed the concurrent
 * activation limit (too many perk effects live at once) — server
 * `details.reason == "activation_limit"`. Distinct from the collapsed
 * [PerkDeployUnavailableException] family so the UI can name the reason.
 */
class PerkDeployActivationLimitException(cause: Throwable? = null) :
    Exception("deployPerk rejected: activation limit.", cause)

/**
 * Reads the Kronjakt shop's DISPLAY catalog + the member's owned inventory and
 * runs the buy. Firebase-free interface so the coordinator/UI are testable with
 * a fake.
 */
interface PerkShopRepository {
    /** LIVE `config/perkCatalog` display mirror; keeps last-known on transient error. */
    fun observeCatalog(): Flow<PerkCatalogState>

    /**
     * LIVE owned-perk counts from `perkInventory/{uid}` as a `{ perkId: count }`
     * map (empty when the doc is absent). Keeps last-known on transient error.
     */
    fun observeInventory(uid: String): Flow<Map<String, Long>>

    /**
     * Buys ONE unit of [perkId] via the `crownHunt-buyPerk` callable. [idempotencyKey]
     * makes a retried call a no-op that debits once. Throws
     * [PerkPurchaseInsufficientFundsException] / [PerkPurchaseUnavailableException]
     * for the two rejection families, and propagates every other failure as-is.
     */
    suspend fun buyPerk(perkId: String, idempotencyKey: String): PerkPurchaseResult

    /**
     * The member's own currently-ACTIVE shield expiry (epoch-ms), or null when
     * no shield is live. Sourced from the owner-readable
     * `perkShieldPublic/{uid}.shieldedUntil` — the private `perkShield` doc is
     * backend-only. Emits null on absence/transient error so the menu still
     * renders. (There is deliberately NO boost equivalent: `perkBoost` has no
     * public mirror, so a live boost is known only from a deploy result.)
     */
    fun observeShieldActiveUntil(uid: String): Flow<Long?>

    /**
     * The EXPIRY timestamps (epoch-ms) of the member's currently-armed traps
     * (`activePerks` where placedByUid == uid, status == 'armed'). The placer is
     * the only client allowed to read their traps (firestore.rules). Returns the
     * expiries — not a pre-computed count — so the caller can re-filter them
     * against a MOVING now (the menu ticker): a trap that expires while the menu
     * is open then drops out without a Firestore re-emit. Emits an empty list on
     * absence/transient error (fail-safe → 0 live traps → the button stays
     * enabled; the server still enforces the cap).
     */
    fun observeActiveTrapExpiries(uid: String): Flow<List<Long>>

    /**
     * Deploys ONE unit of [perkId] via the `crownHunt-deployPerk` callable.
     * [latitude]/[longitude] are the caller's current GPS for a TRAP and ignored
     * for shield/boost. [idempotencyKey] makes a retried call a no-op that
     * consumes once. Throws [PerkDeployUnavailableException] /
     * [PerkDeployMissingLocationException] for the two typed rejection families,
     * and propagates every other failure as-is.
     */
    suspend fun deployPerk(
        perkId: String,
        latitude: Double?,
        longitude: Double?,
        idempotencyKey: String,
    ): PerkDeployResult
}

/**
 * [PerkShopRepository] backed by:
 *  - a member-readable document listener on `config/perkCatalog` (rules gate:
 *    `isActiveMember()`),
 *  - an owner-only document listener on `perkInventory/{uid}` (rules gate:
 *    `get` if owner — a single-doc listener, never a collection query), and
 *  - the `crownHunt-buyPerk` callable (europe-west1).
 *
 * Guarded ([createIfAvailable]): returns null without Firebase, so a config-less
 * build (CI, previews) simply has no shop rather than crashing on first draw.
 */
class FirebasePerkShopRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : PerkShopRepository {

    override fun observeCatalog(): Flow<PerkCatalogState> = callbackFlow {
        // Track the last good load so a transient listener error keeps the shop
        // visible instead of flickering to an error/empty state. Only a failure
        // BEFORE the first successful read surfaces as Error.
        var loadedOnce = false
        val registration =
            firestore.collection(CONFIG).document(PERK_CATALOG).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (!loadedOnce) trySend(PerkCatalogState.Error)
                    return@addSnapshotListener
                }
                val perks = snapshot?.toCatalogEntries().orEmpty()
                loadedOnce = true
                trySend(PerkCatalogState.Loaded(perks))
            }
        awaitClose { registration.remove() }
    }

    override fun observeInventory(uid: String): Flow<Map<String, Long>> = callbackFlow {
        var loadedOnce = false
        val registration =
            firestore.collection(INVENTORY).document(uid).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // First-snapshot error: emit empty so the combine()-built shop
                    // state can still render instead of hanging in Loading forever.
                    // After a successful load, keep the last-known counts on a
                    // transient error rather than misrendering "Du äger: N" as 0.
                    if (!loadedOnce) trySend(emptyMap())
                    return@addSnapshotListener
                }
                loadedOnce = true
                trySend(snapshot.toInventoryCounts())
            }
        awaitClose { registration.remove() }
    }

    override suspend fun buyPerk(perkId: String, idempotencyKey: String): PerkPurchaseResult {
        val payload =
            mapOf<String, Any>(
                "perkId" to perkId,
                "idempotencyKey" to idempotencyKey,
            )
        val response =
            try {
                functions
                    .getHttpsCallable(BUY_PERK)
                    .call(payload)
                    .awaitOrThrow { "buyPerk failed without a cause" }
            } catch (functionsError: FirebaseFunctionsException) {
                throw functionsError.toPerkPurchaseException()
            }
        @Suppress("UNCHECKED_CAST")
        val data = response?.getData() as? Map<String, Any?>
        return data?.toPurchaseResult()
            ?: throw IllegalStateException("buyPerk returned an unrecognized result")
    }

    override fun observeShieldActiveUntil(uid: String): Flow<Long?> = callbackFlow {
        var loadedOnce = false
        var emittedFallback = false
        val registration =
            firestore.collection(SHIELD_PUBLIC).document(uid).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Emit the null fallback at most ONCE before any successful
                    // load, so a persistent error doesn't re-emit it on every
                    // callback. A later success still updates (loadedOnce gate
                    // below), and an error AFTER a success keeps the last value.
                    if (!loadedOnce && !emittedFallback) {
                        emittedFallback = true
                        trySend(null)
                    }
                    return@addSnapshotListener
                }
                loadedOnce = true
                trySend((snapshot?.getTimestamp(SHIELDED_UNTIL))?.toDate()?.time)
            }
        awaitClose { registration.remove() }
    }

    override fun observeActiveTrapExpiries(uid: String): Flow<List<Long>> = callbackFlow {
        // The query's `expiresAt > now` bound is captured once when the listener
        // attaches, so it is set GENEROUSLY into the past (a safety margin) rather
        // than at exactly-now: the precise live/expired cut is done client-side
        // against the menu's moving 15s tick (PerkDeploy.liveTrapCount), so a trap
        // that expires while the menu stays open drops out of the count without a
        // menu reopen. The margin keeps just-expired docs in the emitted set long
        // enough for the tick to be the authority. Uses the composite index
        // activePerks(placedByUid, status, expiresAt) in firestore.indexes.json.
        var loadedOnce = false
        var emittedFallback = false
        val lowerBound = Timestamp(Date(System.currentTimeMillis() - TRAP_QUERY_MARGIN_MS))
        val query =
            firestore
                .collection(ACTIVE_PERKS)
                .whereEqualTo(PLACED_BY_UID, uid)
                .whereEqualTo(STATUS, TRAP_STATUS_ARMED)
                .whereGreaterThan(EXPIRES_AT, lowerBound)
        val registration =
            query.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Fail-safe to empty (never let a read error DISABLE the trap
                    // button — the server still enforces the cap in a
                    // transaction), emitted at most ONCE before any successful
                    // load so a persistent error doesn't re-emit it every
                    // callback. A later success still updates below.
                    if (!loadedOnce && !emittedFallback) {
                        emittedFallback = true
                        trySend(emptyList())
                    }
                    return@addSnapshotListener
                }
                loadedOnce = true
                val expiries =
                    snapshot?.documents.orEmpty().mapNotNull {
                        it.getTimestamp(EXPIRES_AT)?.toDate()?.time
                    }
                trySend(expiries)
            }
        awaitClose { registration.remove() }
    }

    override suspend fun deployPerk(
        perkId: String,
        latitude: Double?,
        longitude: Double?,
        idempotencyKey: String,
    ): PerkDeployResult {
        val payload =
            buildMap<String, Any> {
                put("perkId", perkId)
                put("idempotencyKey", idempotencyKey)
                // Only sent for a trap; the callable ignores them for shield/boost.
                if (latitude != null && longitude != null) {
                    put("latitude", latitude)
                    put("longitude", longitude)
                }
            }
        val response =
            try {
                functions
                    .getHttpsCallable(DEPLOY_PERK)
                    .call(payload)
                    .awaitOrThrow { "deployPerk failed without a cause" }
            } catch (functionsError: FirebaseFunctionsException) {
                throw functionsError.toPerkDeployException()
            }
        @Suppress("UNCHECKED_CAST")
        val data = response?.getData() as? Map<String, Any?>
        return data?.toDeployResult()
            ?: throw IllegalStateException("deployPerk returned an unrecognized result")
    }

    companion object {
        private const val REGION = "europe-west1"
        private const val CONFIG = "config"
        private const val PERK_CATALOG = "perkCatalog"
        private const val INVENTORY = "perkInventory"
        private const val BUY_PERK = "crownHunt-buyPerk"
        private const val DEPLOY_PERK = "crownHunt-deployPerk"
        private const val SHIELD_PUBLIC = "perkShieldPublic"
        private const val SHIELDED_UNTIL = "shieldedUntil"
        private const val ACTIVE_PERKS = "activePerks"
        private const val PLACED_BY_UID = "placedByUid"
        private const val STATUS = "status"
        private const val EXPIRES_AT = "expiresAt"
        private const val TRAP_STATUS_ARMED = "armed"

        /**
         * Safety margin (ms) subtracted from `now` for the armed-trap query's
         * lower bound, so a trap that expires shortly after the listener attaches
         * still arrives in the snapshot and the client-side moving-`now` filter
         * (not the fixed query bound) decides when it drops out of the count.
         */
        private const val TRAP_QUERY_MARGIN_MS = 2 * 60 * 1000L

        fun createIfAvailable(context: Context): PerkShopRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebasePerkShopRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

/**
 * Wire value of the backend's `details.reason` discriminator for the overdraft
 * (insufficient-funds) rejection. Mirrors `PERK_PURCHASE_REASON_INSUFFICIENT_FUNDS`
 * in functions `crownHunt/perks-core.ts`. Every other `FAILED_PRECONDITION`
 * reason (e.g. `shop_unavailable`) — or none at all — is treated as "shop
 * unavailable".
 */
internal const val PERK_REASON_INSUFFICIENT_FUNDS = "insufficient_funds"

/** Mirrors `PERK_PURCHASE_REASON_HOLD_CAP` in functions `crownHunt/perks-core.ts`. */
internal const val PERK_REASON_HOLD_CAP = "hold_cap_reached"

/** Mirrors `PERK_PURCHASE_REASON_COOLDOWN` in functions `crownHunt/perks-core.ts`. */
internal const val PERK_REASON_COOLDOWN = "purchase_cooldown"

/** Mirrors `PERK_DEPLOY_REASON_ACTIVATION_LIMIT` in functions `crownHunt/perks-core.ts`. */
internal const val PERK_DEPLOY_REASON_ACTIVATION_LIMIT = "activation_limit"

/**
 * Maps a callable failure to the two typed rejection families. The overdraft
 * guard and the shop-unavailable guards both surface as `FAILED_PRECONDITION`,
 * so they are told apart by the server's STRUCTURED `details.reason`
 * discriminator (`insufficient_funds` vs everything else) rather than by
 * substring-matching a (localizable) message — mirroring how
 * [com.kungsbackacarcommunity.app.friends.FirebaseFriendsRepository] parses
 * `FirebaseFunctionsException.details`. Anything else propagates unchanged.
 */
private fun FirebaseFunctionsException.toPerkPurchaseException(): Throwable =
    when (code) {
        FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
            perkPurchaseFailedPreconditionException(perkPurchaseReasonOf(details), this)
        else -> this
    }

/**
 * Reads the backend's `details.reason` discriminator out of a callable error's
 * `details` payload (the Android SDK deserializes a JSON object to a [Map]).
 * Returns null when details is absent, not a map, or carries no string reason.
 */
internal fun perkPurchaseReasonOf(details: Any?): String? =
    (details as? Map<*, *>)?.get("reason") as? String

/**
 * Chooses the typed rejection family for a `FAILED_PRECONDITION` from its
 * [reason] discriminator: `insufficient_funds` → [PerkPurchaseInsufficientFundsException];
 * any other reason (including none) → [PerkPurchaseUnavailableException].
 */
internal fun perkPurchaseFailedPreconditionException(
    reason: String?,
    cause: Throwable? = null,
): Throwable =
    when (reason) {
        PERK_REASON_INSUFFICIENT_FUNDS -> PerkPurchaseInsufficientFundsException(cause)
        PERK_REASON_HOLD_CAP -> PerkPurchaseHoldCapException(cause)
        PERK_REASON_COOLDOWN -> PerkPurchaseCooldownException(cause)
        else -> PerkPurchaseUnavailableException(cause)
    }

/**
 * Maps a `deployPerk` callable failure to its typed rejection family. The deploy
 * callable reason-codes EXACTLY ONE rejection — the concurrent-activation limit —
 * via a structured `details.reason`; every OTHER `failed-precondition` carries no
 * reason and is distinguished only by its HttpsError CODE (never by substring-
 * matching a localizable message):
 *  - `invalid-argument` → [PerkDeployMissingLocationException] (a trap with no
 *    valid current position; the only invalid-argument the deploy path throws
 *    for a well-formed request from this client).
 *  - `failed-precondition` with `details.reason == "activation_limit"` →
 *    [PerkDeployActivationLimitException] (too many effects live at once — the
 *    one deploy rejection that carries a structured reason).
 *  - any other `failed-precondition` (no reason) → [PerkDeployUnavailableException]
 *    (flag off, unknown perk, no inventory, trap cap / spacing / daily limit).
 * Anything else (network, internal) propagates unchanged and surfaces as UNKNOWN.
 */
private fun FirebaseFunctionsException.toPerkDeployException(): Throwable =
    when (code) {
        FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
            PerkDeployMissingLocationException(this)
        FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
            if (perkPurchaseReasonOf(details) == PERK_DEPLOY_REASON_ACTIVATION_LIMIT) {
                PerkDeployActivationLimitException(this)
            } else {
                PerkDeployUnavailableException(this)
            }
        else -> this
    }

/** Parses the `crownHunt-deployPerk` callable result payload. */
private fun Map<String, Any?>.toDeployResult(): PerkDeployResult? {
    val perkId = (this["perkId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    val kind = PerkKind.fromWire(this["kind"] as? String) ?: return null
    val effectId = (this["effectId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    val expiresAtMillis = parseIsoMillis(this["expiresAt"] as? String) ?: return null
    val inventoryCount = (this["inventoryCount"] as? Number)?.toLong() ?: return null
    return PerkDeployResult(
        perkId = perkId,
        kind = kind,
        effectId = effectId,
        expiresAtMillis = expiresAtMillis,
        inventoryCount = inventoryCount,
        alreadyDeployed = (this["alreadyDeployed"] as? Boolean) ?: false,
    )
}

/**
 * Parses the callable's ISO-8601 `expiresAt` (e.g. "2026-08-16T12:00:00.000Z")
 * to epoch-ms. Returns null on a blank/unparseable value so a malformed result
 * is skipped rather than shown as a bogus countdown.
 */
private fun parseIsoMillis(iso: String?): Long? {
    val value = iso?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
}

/** Parses the `config/perkCatalog` mirror's `perks` array into display entries. */
private fun DocumentSnapshot.toCatalogEntries(): List<PerkCatalogEntry> {
    if (!exists()) return emptyList()
    @Suppress("UNCHECKED_CAST")
    val rawPerks = get("perks") as? List<Map<String, Any?>> ?: return emptyList()
    return rawPerks.mapNotNull { it.toCatalogEntry() }
}

/**
 * Parses ONE catalog entry. A row missing an id/name/cost, or with an
 * unrecognised kind, is SKIPPED rather than defaulted — a perk shown with a
 * wrong price or family is worse than one briefly absent, and schema drift stays
 * visible instead of being papered over.
 */
private fun Map<String, Any?>.toCatalogEntry(): PerkCatalogEntry? {
    val perkId = (this["perkId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    val kind = PerkKind.fromWire(this["kind"] as? String) ?: return null
    val name = (this["name"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    val costKp = (this["costKp"] as? Number)?.toLong() ?: return null
    if (costKp < 0) return null
    val iconKey = (this["iconKey"] as? String).orEmpty()
    val blurb = (this["blurb"] as? String).orEmpty()
    // nameEn arrives with catalog doc version >= 2; empty on an older mirror.
    val nameEn = (this["nameEn"] as? String).orEmpty()
    return PerkCatalogEntry(
        perkId = perkId,
        kind = kind,
        name = name,
        iconKey = iconKey,
        costKp = costKp,
        blurb = blurb,
        nameEn = nameEn,
    )
}

/**
 * Reads the `{ perkId: count }` counts out of a `perkInventory/{uid}` document,
 * dropping the housekeeping `updatedAt` field and any non-numeric/negative
 * value. An absent document yields an empty map.
 */
private fun DocumentSnapshot?.toInventoryCounts(): Map<String, Long> {
    if (this == null || !exists()) return emptyMap()
    val data = data ?: return emptyMap()
    return buildMap {
        for ((key, value) in data) {
            if (key == UPDATED_AT) continue
            val count = (value as? Number)?.toLong() ?: continue
            if (count >= 0) put(key, count)
        }
    }
}

private const val UPDATED_AT = "updatedAt"

private fun Map<String, Any?>.toPurchaseResult(): PerkPurchaseResult? {
    val perkId = (this["perkId"] as? String) ?: return null
    return PerkPurchaseResult(
        perkId = perkId,
        qty = (this["qty"] as? Number)?.toLong() ?: 1L,
        costKp = (this["costKp"] as? Number)?.toLong() ?: return null,
        newBalance = (this["newBalance"] as? Number)?.toLong() ?: return null,
        inventoryCount = (this["inventoryCount"] as? Number)?.toLong() ?: return null,
        alreadyPurchased = (this["alreadyPurchased"] as? Boolean) ?: false,
    )
}
