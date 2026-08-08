package com.kungsbackacarcommunity.app.dm

import com.kungsbackacarcommunity.app.friends.FriendActionError
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsData
import com.kungsbackacarcommunity.app.friends.FriendsResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NewDialogueCoordinator] — loads the member's friends for the
 * DM inbox's "start a new dialogue" picker (no send step).
 */
class NewDialogueCoordinatorTest {
    private fun friend(uid: String, name: String?) =
        FriendSummary(uid = uid, displayName = name, avatarPath = null, friendsSince = null)

    @Test
    fun `load maps a loaded snapshot to a name-ordered Ready state`() = runTest {
        val friends =
            FriendsData(listOf(friend("u2", "Bo"), friend("u1", "Anna")), emptyList(), emptyList())
        val coordinator = NewDialogueCoordinator { FriendsResult.Loaded(friends) }

        coordinator.load()

        val state = coordinator.state.value
        assertTrue(state is NewDialogueState.Ready)
        assertEquals(listOf("Anna", "Bo"), (state as NewDialogueState.Ready).friends.map { it.displayName })
    }

    @Test
    fun `an empty friend list is a valid Ready state, not an error`() = runTest {
        val coordinator =
            NewDialogueCoordinator { FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList())) }

        coordinator.load()

        val state = coordinator.state.value
        assertTrue(state is NewDialogueState.Ready)
        assertTrue((state as NewDialogueState.Ready).friends.isEmpty())
    }

    @Test
    fun `load maps a failed snapshot to Error`() = runTest {
        val coordinator = NewDialogueCoordinator { FriendsResult.Failed(FriendActionError.Generic) }

        coordinator.load()

        assertEquals(NewDialogueState.Error, coordinator.state.value)
    }
}
