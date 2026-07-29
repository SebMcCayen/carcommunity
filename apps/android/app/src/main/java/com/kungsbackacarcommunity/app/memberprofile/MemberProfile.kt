package com.kungsbackacarcommunity.app.memberprofile

import com.kungsbackacarcommunity.app.badges.Badge
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.profile.SocialHandles

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
    /**
     * The member's canonical social handles. Handles — not URLs — so the
     * rendered link's host is a constant this app owns and can never be
     * redirected by what another member typed (see
     * [com.kungsbackacarcommunity.app.profile.SocialLinks]). An unset platform
     * is null and renders nothing.
     */
    val social: SocialHandles = SocialHandles.EMPTY,
)

/**
 * The other member's badges. `users/{uid}/badges` is readable by any
 * authenticated user (Security Rules), so the expected outcome is now
 * [Available] — badges are a public showcase. [Unavailable] and [Unknown] are
 * kept as the two failure shapes: neither must take the whole profile down, and
 * the two must not be conflated (one is a decision, the other a hiccup).
 */
sealed interface MemberBadges {
    data class Available(val badges: List<Badge>) : MemberBadges

    /**
     * The read was DENIED (PERMISSION_DENIED) — the definitive "these awards
     * aren't visible to you" state rather than a failure. No longer the normal
     * case now that badges are public; it remains reachable if the deployed
     * rules are older than this build, or if a future rule narrows visibility
     * again, and the screen must degrade to a neutral note rather than a retry
     * loop against a read that will never succeed.
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
