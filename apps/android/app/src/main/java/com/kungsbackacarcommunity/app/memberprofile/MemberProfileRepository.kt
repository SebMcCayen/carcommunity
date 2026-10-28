package com.kungsbackacarcommunity.app.memberprofile

import kotlinx.coroutines.flow.map

/**
 * Read-only access to another member's public profile (Android lane, client
 * only). Firebase-free interface so the coordinator/screen are unit- and
 * UI-testable with fakes.
 *
 * Profile details load once. While visible, both avatar badges share the existing
 * public profile listener so membership clearing and cosmetic changes apply live.
 */
interface MemberProfileRepository {
    /** Live cosmetic profile projection only; never reads private subscriptions. */
    fun observeSupporterBadge(uid: String): kotlinx.coroutines.flow.Flow<com.kungsbackacarcommunity.app.profile.SupporterBadge> =
        kotlinx.coroutines.flow.emptyFlow()

    /** Both badges share one users/{uid} snapshot; no extra membership listener. */
    fun observeAvatarBadges(uid: String): kotlinx.coroutines.flow.Flow<MemberAvatarBadges> =
        observeSupporterBadge(uid).map { MemberAvatarBadges(it, null) }

    /**
     * Reads the target member's publicly-readable docs: users/{targetUid}
     * (profile), the `vehicles` owned by them (garage), and — best-effort —
     * their badges. A badge read that is denied by rules collapses to
     * [MemberBadges.Unavailable] instead of failing the whole load.
     */
    suspend fun loadMemberProfile(targetUid: String): MemberProfileResult
}
