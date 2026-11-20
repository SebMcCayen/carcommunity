package com.kungsbackacarcommunity.app.onboarding

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.privacy.PARTNER_STATS_CONSENT_VERSION
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [OnboardingRepository] backed by the auth.completeOnboarding callable
 * (Phase 12 slice 2).
 *
 * All Cloud Functions deploy to europe-west1 (docs/api-guidelines.md); the
 * deployed callable name is the grouped-export form `auth-completeOnboarding`
 * (contracts/functions/functions.json). The Task is bridged to a coroutine
 * with the same isActive-guarded pattern as FirebaseAuthRepository — no
 * play-services-await dependency.
 *
 * Construction is guarded ([createIfAvailable] returns null when Firebase
 * is not configured, i.e. no google-services.json in CI/validation builds).
 */
class FirebaseOnboardingRepository private constructor(
    private val functions: FirebaseFunctions,
) : OnboardingRepository {

    override suspend fun completeOnboarding(
        displayName: String?,
        anonymousPartnerStatsOptIn: Boolean?,
    ) {
        val data = onboardingConsentPayload(displayName, anonymousPartnerStatsOptIn)
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(CALLABLE_NAME)
                .call(data)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception
                                ?: IllegalStateException("completeOnboarding failed without a cause"),
                        )
                    }
                }
        }
    }

    companion object {
        private const val REGION = "europe-west1"
        private const val CALLABLE_NAME = "auth-completeOnboarding"

        fun createIfAvailable(context: Context): OnboardingRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseOnboardingRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}

/** Consent metadata is present only when the optional choice was displayed and submitted. */
internal fun onboardingConsentPayload(displayName: String?, partnerStatsOptIn: Boolean?): Map<String, Any> =
    buildMap {
        put("licenceConfirmed", true)
        put("termsAccepted", true)
        put("privacyPolicyAccepted", true)
        if (displayName != null) put("displayName", displayName)
        if (partnerStatsOptIn != null) {
            put("anonymousPartnerStatsOptIn", partnerStatsOptIn)
            put("anonymousPartnerStatsConsentVersion", PARTNER_STATS_CONSENT_VERSION)
        }
    }
