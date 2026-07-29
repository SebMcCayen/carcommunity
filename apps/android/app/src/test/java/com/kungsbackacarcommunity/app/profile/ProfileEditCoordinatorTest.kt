package com.kungsbackacarcommunity.app.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileEditCoordinatorTest {

    private class FakeRepo : ProfileRepository {
        val updates = mutableListOf<Triple<String, String, String>>()
        val socials = mutableListOf<SocialHandles>()
        var failWith: Exception? = null

        override fun observeProfile(uid: String): Flow<ProfileState> =
            flowOf(ProfileState.Loaded(null))

        override suspend fun updateProfile(
            uid: String,
            displayName: String,
            bio: String,
            social: SocialHandles,
        ) {
            failWith?.let { throw it }
            updates += Triple(uid, displayName, bio)
            socials += social
        }

        override suspend fun updateAvatarPath(uid: String, avatarPath: String) {
            failWith?.let { throw it }
        }
    }

    @Test
    fun `successful save writes the fields and ends Saved`() = runTest {
        val repo = FakeRepo()
        val coordinator = ProfileEditCoordinator(repo)
        coordinator.save("u1", "Sebbe", "bio")
        assertEquals(listOf(Triple("u1", "Sebbe", "bio")), repo.updates)
        assertEquals(ProfileEditStatus.Saved, coordinator.status.value)
    }

    @Test
    fun `save forwards the canonical handles it was given`() = runTest {
        val repo = FakeRepo()
        val coordinator = ProfileEditCoordinator(repo)
        val handles = SocialHandles(instagram = "sebmccayen", youtube = "SebMcCayen")
        coordinator.save("u1", "Sebbe", "bio", handles)
        assertEquals(listOf(handles), repo.socials)
    }

    @Test
    fun `failed save surfaces Failed and reset returns to Idle`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("denied") }
        val coordinator = ProfileEditCoordinator(repo)
        coordinator.save("u1", "Sebbe", "bio")
        assertEquals(ProfileEditStatus.Failed, coordinator.status.value)
        coordinator.reset()
        assertEquals(ProfileEditStatus.Idle, coordinator.status.value)
    }
}
