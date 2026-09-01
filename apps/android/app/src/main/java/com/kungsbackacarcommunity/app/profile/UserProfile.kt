package com.kungsbackacarcommunity.app.profile

/**
 * The signed-in user's public profile (users/{uid}), Phase 12 slice 2.
 * Only the fields the client reads/edits are modelled.
 */
data class UserProfile(
    val displayName: String?,
    val bio: String?,
    /**
     * Cloud Storage path of the avatar (profileImages/{uid}/{imageId}), or null
     * when unset. The path — not a URL — is stored; a URL is resolved lazily for
     * rendering (media/StorageImageUrl).
     */
    val avatarPath: String? = null,
    /** True once auth.completeOnboarding has stamped onboardingCompletedAt. */
    val onboardingComplete: Boolean,
    /**
     * Backend-managed member entitlement (users/{uid}.activeMember). Drives
     * the client-side member-feature gate; the backend still enforces it.
     */
    val activeMember: Boolean = false,
    /**
     * Backend-managed admin/owner role (users/{uid}.role in {admin, owner}).
     * True for a staff account, which the backend admits to admin-bypass paths
     * regardless of subscription (canAccessAdminFeatures). Read from the SAME
     * owner-readable profile snapshot already observed — no extra query, no new
     * mechanism. Used only to keep a client-side paid-feature gate consistent
     * with the server (e.g. the event-details roster, which the backend always
     * serves to admins); the backend stays the enforcement boundary.
     */
    val isAdmin: Boolean = false,
    /**
     * Epoch-millis of users/{uid}.createdAt (the account/profile creation
     * server timestamp), or null when the field is absent (a partially-written
     * doc, or an older account predating the field). Read from the SAME profile
     * snapshot already observed — no extra query — and used only for the
     * "member since" line on the owner's own stats. Never edited by the client.
     */
    val createdAtMillis: Long? = null,
    /**
     * Canonical social handles (users/{uid}.facebook/.instagram/.youtube).
     * PUBLIC by intent — users/{uid} is readable by any signed-in member, and
     * the edit form says so in as many words. A platform the member has not
     * filled in is ABSENT from the document, so [SocialHandles.EMPTY] is the
     * normal state and renders nothing at all.
     */
    val social: SocialHandles = SocialHandles.EMPTY,
)

/** Observed state of the profile document. */
sealed interface ProfileState {
    /** Still loading the first snapshot. */
    data object Loading : ProfileState

    /** Firebase is not configured in this build (no google-services.json). */
    data object Unavailable : ProfileState

    /** Snapshot resolved; [profile] is null when the document does not exist yet. */
    data class Loaded(val profile: UserProfile?) : ProfileState

    /** The snapshot listener reported an error (permission/offline/misconfig). */
    data object Error : ProfileState
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
        // A read error renders the main shell (never an infinite spinner);
        // the listener self-corrects to Loaded on a later successful snapshot.
        ProfileState.Error -> AuthedDestination.Main
        is ProfileState.Loaded ->
            if (state.profile?.onboardingComplete == true) {
                AuthedDestination.Main
            } else {
                AuthedDestination.Onboarding
            }
    }
