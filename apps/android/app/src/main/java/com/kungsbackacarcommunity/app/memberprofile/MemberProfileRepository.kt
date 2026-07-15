package com.kungsbackacarcommunity.app.memberprofile

/**
 * Read-only access to another member's public profile (Android lane, client
 * only). Firebase-free interface so the coordinator/screen are unit- and
 * UI-testable with fakes.
 *
 * A one-shot read (not a live listener): a profile view is a transient screen,
 * so it fetches the readable docs once on open rather than holding snapshot
 * listeners on another user's data.
 */
interface MemberProfileRepository {
    /**
     * Reads the target member's publicly-readable docs: users/{targetUid}
     * (profile), the `vehicles` owned by them (garage), and — best-effort —
     * their badges. A badge read that is denied by rules collapses to
     * [MemberBadges.Unavailable] instead of failing the whole load.
     */
    suspend fun loadMemberProfile(targetUid: String): MemberProfileResult
}
