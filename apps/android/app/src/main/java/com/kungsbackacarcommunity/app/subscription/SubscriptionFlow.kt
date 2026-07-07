package com.kungsbackacarcommunity.app.subscription

/**
 * Pure Kotlin core for the subscriptions slice (Phase 12 slice 24). No Android
 * or Firebase imports so it is JVM-unit-testable. Holds the product id, the
 * verify-payload builder, and the [PurchaseFlowStatus] state machine that the
 * coordinator drives.
 */

/** The Play Console product id for the monthly membership subscription. */
const val SUBSCRIPTION_PRODUCT: String = "member_monthly"

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

/** Why a purchase flow failed, so the UI can pick the right localized copy. */
enum class PurchaseFailureReason {
    /** The billing client could not connect to the store. */
    Connection,

    /** The `member_monthly` product could not be queried / is unavailable. */
    ProductUnavailable,

    /** The Play purchase flow itself failed or was not completed. */
    Purchase,

    /** The purchase completed but `subscription-verify` did not confirm it. */
    Verification,

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
            -> false
        }

    /** True while the flow is mid-flight and should block re-entry / show progress. */
    fun isInFlight(status: PurchaseFlowStatus): Boolean = !canStart(status)

    fun failed(reason: PurchaseFailureReason): PurchaseFlowStatus =
        PurchaseFlowStatus.Failed(reason)
}
