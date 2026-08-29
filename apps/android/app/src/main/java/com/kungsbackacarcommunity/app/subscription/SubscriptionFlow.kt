package com.kungsbackacarcommunity.app.subscription

import java.security.MessageDigest

/**
 * Pure Kotlin core for the subscriptions slice (Phase 12 slice 24). No Android
 * or Firebase imports so it is JVM-unit-testable. Holds the product id, the
 * verify-payload builder, and the [PurchaseFlowStatus] state machine that the
 * coordinator drives.
 */

/** Immutable Play product ids for the future tiered monthly plans. */
const val PLUS_MONTHLY_PRODUCT_ID: String = "plus_monthly"
const val SUPPORTER_MONTHLY_PRODUCT_ID: String = "supporter_monthly"
const val MONTHLY_BASE_PLAN_ID: String = "monthly"

val SUBSCRIPTION_PRODUCT_IDS: Set<String> =
    setOf(PLUS_MONTHLY_PRODUCT_ID, SUPPORTER_MONTHLY_PRODUCT_ID)

/**
 * One-way Play account binding. SHA-256 hex is exactly 64 characters, contains
 * no raw Firebase UID/PII, and is recomputed independently by the backend.
 */
fun obfuscatedAccountIdForUid(uid: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(uid.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

/** Defense-in-depth before handing the account binding to Google Play. */
fun isValidObfuscatedAccountId(value: String): Boolean = value.matches(Regex("^[a-f0-9]{64}$"))

/** The platform tag sent to the `subscription-verify` callable from Android. */
const val VERIFY_PLATFORM_GOOGLE: String = "google"

/**
 * Builds the payload for the `subscription-verify` callable. Mirrors the
 * backend contract `{ platform: "apple"|"google", purchaseToken: string }`;
 * Android always sends `"google"`.
 */
fun buildVerifyPayload(purchaseToken: String): Map<String, Any?> =
    mapOf(
        "platform" to VERIFY_PLATFORM_GOOGLE,
        "purchaseToken" to purchaseToken,
    )

data class SubscriptionVerificationResult(
    val entitlement: String,
    val status: String,
    val tier: String,
) {
    val grantsAccess: Boolean
        get() =
            entitlement == "member_monthly" &&
                tier in setOf("plus", "supporter") &&
                status in setOf("active", "grace_period", "cancelled")
}

/** Rejects malformed callable data rather than treating it as a successful purchase. */
fun parseVerificationResult(value: Any?): SubscriptionVerificationResult {
    val data = value as? Map<*, *> ?: error("subscription.verify returned malformed data")
    val entitlement = data["entitlement"] as? String ?: error("Missing entitlement")
    val status = data["status"] as? String ?: error("Missing subscription status")
    val tier = data["tier"] as? String ?: error("Missing subscription tier")
    if (entitlement !in setOf("none", "member_monthly")) error("Unknown entitlement")
    if (status !in setOf("inactive", "active", "grace_period", "expired", "revoked", "cancelled")) {
        error("Unknown subscription status")
    }
    if (tier !in setOf("community", "plus", "supporter")) error("Unknown subscription tier")
    if (entitlement == "member_monthly" && tier == "community") {
        error("Paid entitlement cannot use Community tier")
    }
    return SubscriptionVerificationResult(entitlement, status, tier)
}

/** Picks one authoritative-looking owned purchase and avoids lower-tier overwrite races. */
fun preferredPurchaseForReconciliation(purchases: List<OwnedPurchase>): OwnedPurchase? =
    purchases.maxWithOrNull(
        compareBy<OwnedPurchase> { it.state == OwnedPurchaseState.Purchased }
            .thenBy { SUPPORTER_MONTHLY_PRODUCT_ID in it.productIds }
            .thenBy { PLUS_MONTHLY_PRODUCT_ID in it.productIds },
    )

/** Why a purchase flow failed, so the UI can pick the right localized copy. */
enum class PurchaseFailureReason {
    /** The billing client could not connect to the store. */
    Connection,

    /** The selected Plus or Supporter product could not be queried / is unavailable. */
    ProductUnavailable,

    /** The Play purchase flow itself failed or was not completed. */
    Purchase,

    /** The purchase completed but `subscription-verify` did not confirm it. */
    Verification,

    /** Play accepted payment but the secure backend did not confirm active access. */
    InactivePurchase,

    /** Billing is not available on this build/device (no Play, config-less). */
    Unavailable,
}

/**
 * UI-facing status of the purchase → verify flow. A linear happy path
 * (Idle → Connecting → Ready → Purchasing → Verifying → Success) with a
 * [Failed] terminal that carries the [PurchaseFailureReason].
 */
sealed interface PurchaseFlowStatus {
    data object Idle : PurchaseFlowStatus

    data object Connecting : PurchaseFlowStatus

    data object Ready : PurchaseFlowStatus

    data object Purchasing : PurchaseFlowStatus

    data object Verifying : PurchaseFlowStatus

    data object Success : PurchaseFlowStatus

    /** Play reports a pending payment; no entitlement is granted or acknowledged. */
    data object Pending : PurchaseFlowStatus

    data class Failed(val reason: PurchaseFailureReason) : PurchaseFlowStatus
}

/**
 * Pure state-transition helpers for [PurchaseFlowStatus]. Kept separate from the
 * coordinator so the transitions can be asserted directly in unit tests.
 */
object PurchaseFlow {
    /** A new purchase may only be launched from a settled (non-in-flight) state. */
    fun canStart(status: PurchaseFlowStatus): Boolean =
        when (status) {
            PurchaseFlowStatus.Idle,
            PurchaseFlowStatus.Ready,
            PurchaseFlowStatus.Success,
            is PurchaseFlowStatus.Failed,
            -> true
            PurchaseFlowStatus.Connecting,
            PurchaseFlowStatus.Purchasing,
            PurchaseFlowStatus.Verifying,
            PurchaseFlowStatus.Pending,
            -> false
        }

    /** True while the flow is mid-flight and should block re-entry / show progress. */
    fun isInFlight(status: PurchaseFlowStatus): Boolean = !canStart(status)

    fun failed(reason: PurchaseFailureReason): PurchaseFlowStatus =
        PurchaseFlowStatus.Failed(reason)
}
