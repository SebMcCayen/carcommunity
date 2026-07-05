package com.kungsbackacarcommunity.app.profile

/**
 * The signed-in user's public profile (users/{uid}), Phase 12 slice 2.
 * Only the fields the client reads/edits are modelled.
 */
data class UserProfile(
    val displayName: String?,
    val bio: String?,
    /** True once auth.completeOnboarding has stamped onboardingCompletedAt. */
    val onboardingComplete: Boolean,
)

/** Observed state of the profile document. */
sealed interface ProfileState {
    /** Still loading the first snapshot. */
    data object Loading : ProfileState

    /** Firebase is not configured in this build (no google-services.json). */
    data object Unavailable : ProfileState

    /** Snapshot resolved; [profile] is null when the document does not exist yet. */
    data class Loaded(val profile: UserProfile?) : ProfileState
}

/** Top-level authenticated destination derived from [ProfileState]. */
enum class AuthedDestination { Loading, Onboarding, Main }

/**
 * Pure routing decision for a signed-in user (Phase 12 slice 2):
 * onboarding is required until the profile document reports it complete.
 * The Unavailable (no-Firebase) build renders the main shell so CI /
 * validation builds are navigable.
 */
fun authedDestination(state: ProfileState): AuthedDestination =
    when (state) {
        ProfileState.Loading -> AuthedDestination.Loading
        ProfileState.Unavailable -> AuthedDestination.Main
        is ProfileState.Loaded ->
            if (state.profile?.onboardingComplete == true) {
                AuthedDestination.Main
            } else {
                AuthedDestination.Onboarding
            }
    }
