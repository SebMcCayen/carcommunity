package com.kungsbackacarcommunity.app.subscription

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Verifies a Play purchase server-side (Phase 12 slice 24). Firebase-free
 * interface so the coordinator is testable with a fake; the real implementation
 * calls the `subscription-verify` callable (region europe-west1) exactly like
 * the other slices call their callables.
 *
 * IMPORTANT: `subscription-verify` FAILS CLOSED until real store credentials are
 * configured in the backend (a cutover/console step). Until then this call
 * throws and the flow surfaces [PurchaseFailureReason.Verification]; no
 * entitlement is granted. End-to-end entitlement therefore cannot be verified
 * from this slice.
 */
interface SubscriptionVerifier {
    suspend fun verify(purchaseToken: String)
}

/** [SubscriptionVerifier] backed by the `subscription-verify` callable. */
class FirebaseSubscriptionVerifier private constructor(
    private val functions: FirebaseFunctions,
) : SubscriptionVerifier {

    override suspend fun verify(purchaseToken: String): Unit =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(VERIFY)
                .call(buildVerifyPayload(purchaseToken))
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("$VERIFY failed without a cause"),
                        )
                    }
                }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val VERIFY = "subscription-verify"

        fun createIfAvailable(context: Context): SubscriptionVerifier? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseSubscriptionVerifier(FirebaseFunctions.getInstance(REGION))
        }
    }
}
