package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
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

/**
 * Thrown when the buy was rejected because the member cannot afford the perk —
 * the server's ledger debit hit the `failed-precondition` overdraft guard.
 * Distinct from [PerkPurchaseUnavailableException] so the UI shows a "not enough
 * KP" hint rather than a generic error.
 */
class PerkPurchaseInsufficientFundsException(cause: Throwable? = null) :
    Exception("buyPerk rejected: insufficient Kronpoäng.", cause)

/**
 * Thrown when the shop itself refused the buy for a non-affordability reason:
 * the `crownHuntPerks` flag is off, the account cannot spend, or the perk id is
 * unknown. All three share the server's `failed-precondition` code, so the UI
 * treats them as a single "shop unavailable" message.
 */
class PerkPurchaseUnavailableException(cause: Throwable? = null) :
    Exception("buyPerk rejected: shop unavailable.", cause)

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

    companion object {
        private const val REGION = "europe-west1"
        private const val CONFIG = "config"
        private const val PERK_CATALOG = "perkCatalog"
        private const val INVENTORY = "perkInventory"
        private const val BUY_PERK = "crownHunt-buyPerk"

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
 * Maps a callable failure to the two typed rejection families. The overdraft
 * guard and the shop-unavailable guards both surface as `FAILED_PRECONDITION`,
 * so the overdraft is told apart by its server message (which mentions a
 * negative balance); every other `FAILED_PRECONDITION` is "unavailable".
 * Anything else propagates unchanged.
 */
private fun FirebaseFunctionsException.toPerkPurchaseException(): Throwable =
    when (code) {
        FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
            if (message?.contains("negative balance", ignoreCase = true) == true) {
                PerkPurchaseInsufficientFundsException(this)
            } else {
                PerkPurchaseUnavailableException(this)
            }
        else -> this
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
    return PerkCatalogEntry(
        perkId = perkId,
        kind = kind,
        name = name,
        iconKey = iconKey,
        costKp = costKp,
        blurb = blurb,
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
