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

    /**
     * Genuinely not visible to this viewer: the read was denied
     * (PERMISSION_DENIED) under the current owner-only rules. A definitive
     * "awards aren't shown on other members' profiles" state, not a failure.
     */
    data object Unavailable : MemberBadges

    /**
     * The read failed for a transient/unknown reason (offline, timeout,
     * backend misconfig) — NOT a permission denial. Surfaced as a
     * reason-agnostic "couldn't load" note so a temporary hiccup isn't
     * misreported as the definitive [Unavailable] explanation.
     */
    data object Unknown : MemberBadges
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
     * The member can't be shown: the users/{uid} document does not exist, or it
     * carries no usable identity. A neutral "unavailable" notice.
     */
    data object Unavailable : MemberProfileState

    /**
     * The VIEWER has blocked this member, so their profile is withheld —
     * rendered as a neutral notice plus an Unblock action.
     *
     * Split out of [Unavailable] (which it previously collapsed into) so the
     * viewer can undo their own block from the same surface they made it on.
     * This reveals nothing: a block is directional, this state is only ever
     * reachable by the viewer who created the block, the blocked member never
     * sees this screen, and that viewer can already enumerate exactly these uids
     * under Settings → Blocked users. A genuinely missing profile still collapses
     * to [Unavailable], so "blocked" remains un-inferable from an absent member.
     */
    data object Blocked : MemberProfileState

    data object Error : MemberProfileState

    data class Loaded(
        val profile: MemberProfile,
        val vehicles: List<Vehicle>,
        val badges: MemberBadges,
    ) : MemberProfileState
}
