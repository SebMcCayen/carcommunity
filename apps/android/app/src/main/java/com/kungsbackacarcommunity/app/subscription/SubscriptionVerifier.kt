package com.kungsbackacarcommunity.app.subscription

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow

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
    suspend fun verify(purchaseToken: String): SubscriptionVerificationResult
}

/** [SubscriptionVerifier] backed by the `subscription-verify` callable. */
class FirebaseSubscriptionVerifier private constructor(
    private val functions: FirebaseFunctions,
    private val auth: FirebaseAuth,
) : SubscriptionVerifier {

    override suspend fun verify(purchaseToken: String): SubscriptionVerificationResult {
        val callableResult =
            functions
                .getHttpsCallable(VERIFY)
                .call(buildVerifyPayload(purchaseToken))
                .awaitOrThrow { "$VERIFY failed without a cause" }
        val verified = parseVerificationResult(callableResult.data)
        if (verified.grantsAccess) {
            // applyEntitlement updates the activeMember custom claim. Force a
            // fresh ID token before reporting success so RTDB/Firestore gates do
            // not keep using the pre-purchase claim until normal token refresh.
            val user = auth.currentUser ?: error("No authenticated user after subscription verify")
            user.getIdToken(true).awaitOrThrow { "Firebase ID token refresh failed" }
        }
        return verified
    }

    companion object {
        private const val REGION = "europe-west1"
        private const val VERIFY = "subscription-verify"

        fun createIfAvailable(context: Context): SubscriptionVerifier? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseSubscriptionVerifier(
                functions = FirebaseFunctions.getInstance(REGION),
                auth = FirebaseAuth.getInstance(),
            )
        }
    }
}
