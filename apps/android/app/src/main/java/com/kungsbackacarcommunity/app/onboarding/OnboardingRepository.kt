package com.kungsbackacarcommunity.app.onboarding

/**
 * Onboarding boundary (Phase 12 slice 2). Firebase-free so the
 * orchestration is unit-testable with fakes.
 */
interface OnboardingRepository {
    /**
     * Calls auth.completeOnboarding with the three mandatory consents set
     * and an optional display name. The backend writes onboardingCompletedAt
     * and the consent timestamps with server timestamps.
     *
     * [anonymousPartnerStatsOptIn] carries the anonymised-partner-statistics
     * choice (default-on / opt-out). null omits the field so the backend keeps
     * the provisioning default (ON); false persists an explicit opt-out.
     *
     * @throws Exception when the callable fails (network, auth, validation).
     */
    suspend fun completeOnboarding(displayName: String?, anonymousPartnerStatsOptIn: Boolean? = null)
}
