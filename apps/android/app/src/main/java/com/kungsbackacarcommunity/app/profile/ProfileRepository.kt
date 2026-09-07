package com.kungsbackacarcommunity.app.profile

import kotlinx.coroutines.flow.Flow

/**
 * Profile boundary (Phase 12 slice 2). Firebase-free so callers can be
 * unit-tested with fakes.
 */
interface ProfileRepository {
    /** Emits the live users/{uid} profile state (snapshot listener). */
    fun observeProfile(uid: String): Flow<ProfileState>

    /**
     * Direct owner write of the whitelisted profile fields (Phase 9a rules:
     * displayName, bio, the three social handles, server-timestamp updatedAt).
     *
     * [social] must already be CANONICAL — the handles ProfileValidation
     * produced, never raw member input. A null entry CLEARS that platform
     * (the field is removed from the document), so an unset platform has one
     * representation and the public profile can decide on presence alone.
     *
     * @throws Exception when the write is rejected (rules, network).
     */
    suspend fun updateProfile(
        uid: String,
        displayName: String,
        bio: String,
        social: SocialHandles = SocialHandles.EMPTY,
    )

    /**
     * Direct owner write of the avatar path only (rules whitelist: avatarPath +
     * updatedAt). [avatarPath] must be profileImages/{uid}/... (≤500 chars); the
     * rules re-validate it. Called after a successful avatar upload.
     *
     * @throws Exception when the write is rejected (rules, network).
     */
    suspend fun updateAvatarPath(uid: String, avatarPath: String)

    /** Owner preference only; never writes eligibility or subscription data. */
    suspend fun updateShowSupporterBadge(uid: String, show: Boolean)
}
