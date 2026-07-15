package com.kungsbackacarcommunity.app.memberprofile

import com.kungsbackacarcommunity.app.badges.Badge
import com.kungsbackacarcommunity.app.garage.Vehicle

/**
 * Read-only view of ANOTHER member's public profile (users/{uid}). Only the
 * publicly-readable fields are modelled — the same display fields the owner's
 * [com.kungsbackacarcommunity.app.profile.UserProfile] exposes, minus the
 * owner-only entitlement/onboarding flags. Pure Kotlin — JVM-testable.
 */
data class MemberProfile(
    val uid: String,
    val displayName: String?,
    val bio: String?,
    /** Cloud Storage path of the avatar; a URL is resolved lazily for rendering. */
    val avatarPath: String? = null,
)

/**
 * The other member's badges. Under the current Security Rules
 * (users/{uid}/badges is owner-only read), another user's badges are NOT
 * readable — so a read attempt is expected to be denied and collapses to
 * [Unavailable] rather than erroring the whole screen. When a future backend
 * rule/callable exposes them publicly this becomes [Available].
 */
sealed interface MemberBadges {
    data class Available(val badges: List<Badge>) : MemberBadges

    /** Not readable under current rules (or the read failed) — hidden gracefully. */
    data object Unavailable : MemberBadges
}

/**
 * The repository's one-shot read outcome for a target member. Blocking is
 * decided a layer up (the coordinator), so it is deliberately absent here.
 */
sealed interface MemberProfileResult {
    /** The users/{uid} document does not exist (or carries no usable identity). */
    data object NotFound : MemberProfileResult

    /** The profile read failed (permission/offline/misconfig). */
    data object Error : MemberProfileResult

    data class Loaded(
        val profile: MemberProfile,
        val vehicles: List<Vehicle>,
        val badges: MemberBadges,
    ) : MemberProfileResult
}

/** UI-facing state of the member-profile screen. */
sealed interface MemberProfileState {
    data object Loading : MemberProfileState

    /**
     * The member can't be shown: either the viewer has blocked them, or the
     * profile does not exist. Rendered as a single neutral "unavailable" notice
     * — deliberately not distinguishing block from not-found.
     */
    data object Unavailable : MemberProfileState

    data object Error : MemberProfileState

    data class Loaded(
        val profile: MemberProfile,
        val vehicles: List<Vehicle>,
        val badges: MemberBadges,
    ) : MemberProfileState
}
