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
     * displayName, bio, server-timestamp updatedAt).
     *
     * @throws Exception when the write is rejected (rules, network).
     */
    suspend fun updateProfile(uid: String, displayName: String, bio: String)

    /**
     * Direct owner write of the avatar path only (rules whitelist: avatarPath +
     * updatedAt). [avatarPath] must be profileImages/{uid}/... (≤500 chars); the
     * rules re-validate it. Called after a successful avatar upload.
     *
     * @throws Exception when the write is rejected (rules, network).
     */
    suspend fun updateAvatarPath(uid: String, avatarPath: String)
}
