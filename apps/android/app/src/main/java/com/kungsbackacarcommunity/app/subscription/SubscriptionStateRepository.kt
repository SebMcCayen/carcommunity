package com.kungsbackacarcommunity.app.subscription

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Safe, token-free projection of the authoritative subscriptions/{uid} record. */
data class StoredSubscription(
    val tier: String,
    val status: String,
    val entitlement: String,
    val platform: String,
) {
    val grantsAccess: Boolean
        get() =
            entitlement == "member_monthly" &&
                tier in setOf("plus", "supporter") &&
                status in setOf("active", "grace_period", "cancelled")

    val googleProductId: String?
        get() =
            if (platform != "google") {
                null
            } else {
                when (tier) {
                    "plus" -> PLUS_MONTHLY_PRODUCT_ID
                    "supporter" -> SUPPORTER_MONTHLY_PRODUCT_ID
                    else -> null
                }
            }
}

const val COMMUNITY_GARAGE_VEHICLE_LIMIT = 2
const val PLUS_GARAGE_VEHICLE_LIMIT = 5
const val SUPPORTER_GARAGE_VEHICLE_LIMIT = 10

enum class EffectiveSubscriptionTier {
    COMMUNITY,
    PLUS,
    SUPPORTER,
}

val StoredSubscription?.effectiveTier: EffectiveSubscriptionTier
    get() =
        when {
            this?.grantsAccess != true -> EffectiveSubscriptionTier.COMMUNITY
            tier == "supporter" -> EffectiveSubscriptionTier.SUPPORTER
            else -> EffectiveSubscriptionTier.PLUS
        }

/**
 * Client-side mirror of the authoritative callable limits. Missing, malformed,
 * inactive, expired, or revoked records are Community. The backend remains the
 * enforcement boundary; this value only keeps the Garage UI honest.
 */
val StoredSubscription?.garageVehicleLimit: Int
    get() =
        when (effectiveTier) {
            EffectiveSubscriptionTier.COMMUNITY -> COMMUNITY_GARAGE_VEHICLE_LIMIT
            EffectiveSubscriptionTier.PLUS -> PLUS_GARAGE_VEHICLE_LIMIT
            EffectiveSubscriptionTier.SUPPORTER -> SUPPORTER_GARAGE_VEHICLE_LIMIT
        }

/** Owner-readable subscription state. Raw purchase tokens never cross this boundary. */
interface SubscriptionStateRepository {
    fun observeSubscription(uid: String): Flow<StoredSubscription?>
}

class FirebaseSubscriptionStateRepository private constructor(
    private val firestore: FirebaseFirestore,
) : SubscriptionStateRepository {
    override fun observeSubscription(uid: String): Flow<StoredSubscription?> = callbackFlow {
        val registration =
            firestore.collection(SUBSCRIPTIONS).document(uid).addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(
                    parseStoredSubscription(
                        tier = snapshot.getString("tier"),
                        status = snapshot.getString("status"),
                        entitlement = snapshot.getString("entitlement"),
                        platform = snapshot.getString("platform"),
                    ),
                )
            }
        awaitClose { registration.remove() }
    }

    companion object {
        private const val SUBSCRIPTIONS = "subscriptions"

        fun createIfAvailable(context: Context): SubscriptionStateRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseSubscriptionStateRepository(FirebaseFirestore.getInstance())
        }
    }
}

/** Rejects malformed backend records instead of inventing a paid tier. */
fun parseStoredSubscription(
    tier: String?,
    status: String?,
    entitlement: String?,
    platform: String?,
): StoredSubscription? {
    val safeStatus = status ?: return null
    val safeEntitlement = entitlement ?: return null
    val safePlatform = platform ?: return null
    // The original member_monthly record predates explicit tiers and is Plus.
    val safeTier =
        tier ?: if (safeEntitlement == "member_monthly") "plus" else "community"
    if (safeTier !in setOf("community", "plus", "supporter")) return null
    if (safeStatus !in setOf("inactive", "active", "grace_period", "expired", "revoked", "cancelled")) {
        return null
    }
    if (safeEntitlement !in setOf("none", "member_monthly")) return null
    if (safePlatform !in setOf("apple", "google", "manual")) return null
    if (safeEntitlement == "member_monthly" && safeTier == "community") return null
    return StoredSubscription(
        tier = safeTier,
        status = safeStatus,
        entitlement = safeEntitlement,
        platform = safePlatform,
    )
}
