package com.kungsbackacarcommunity.app.clubs

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class ClubChatOutboxTest {
    private val member = ClubMembership("club", ClubRole.Member, "joined-first")
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = value is String
    }
    private val row = ClubPendingSend("operation", "message", "Svenska tecken 🚗\nNästa rad", submittedAtMillis = 123L)

    private fun saved(owner: ClubChatOutbox): Any = with(clubChatOutboxSaver(owner.uid)) {
        with(scope) { save(owner) }!!
    }

    @Test fun activityPayloadRetainsSubmittedTextIdsAndTimeWithoutAutomaticDispatch() {
        val owner = ClubChatOutbox("self")
        owner.open(member).pending.value = listOf(row)
        val payload = saved(owner)
        assertTrue((payload as List<*>).all { it is String })
        val restored = clubChatOutboxSaver("self").restore(payload)!!
        assertEquals(listOf(row.copy(failed = true)), restored.open(member).pending.value)
    }

    @Test fun acceptedAndFailedMessagesRetainTheirIndependentDeliveryStates() {
        val owner = ClubChatOutbox("self")
        val rows = listOf(row.copy(failed = true), row.copy(operationId = "accepted", accepted = true))
        owner.open(member).pending.value = rows
        val restored = clubChatOutboxSaver("self").restore(saved(owner))!!
        assertEquals(rows, restored.open(member).pending.value)
    }

    @Test fun freshRoomDetachesCancelledCallbacksFromTheResumedRetry() {
        val owner = ClubChatOutbox("self")
        val previousRoom = owner.open(member)
        previousRoom.pending.value = listOf(row)
        val resumedRoom = owner.open(member)
        assertNotSame(previousRoom, resumedRoom)
        assertEquals(listOf(row.copy(failed = true)), resumedRoom.pending.value)
        resumedRoom.pending.value = listOf(row.copy(accepted = true))
        previousRoom.pending.value = listOf(row.copy(failed = true))
        assertEquals(listOf(row.copy(accepted = true)), owner.current!!.pending.value)
    }

    @Test fun switchingClubsOrRejoiningCannotRecoverAnOldMembershipOutbox() {
        val owner = ClubChatOutbox("self")
        val previous = owner.open(member)
        previous.pending.value = listOf(row)
        assertTrue(owner.open(member.copy(clubId = "other")).pending.value.isEmpty())
        previous.pending.value = listOf(row.copy(accepted = true))
        assertTrue(owner.current!!.pending.value.isEmpty())
        owner.open(member).pending.value = listOf(row)
        assertTrue(owner.open(member.copy(joinedAt = "joined-later")).pending.value.isEmpty())
    }

    @Test fun aRoleChangeWithinTheSameMembershipKeepsTheRecoverableSend() {
        val owner = ClubChatOutbox("self")
        owner.open(member).pending.value = listOf(row)
        assertEquals(listOf(row.copy(failed = true)), owner.open(member.copy(role = ClubRole.Moderator)).pending.value)
    }

    @Test fun anAccountMismatchInSavedStateRestoresNothing() {
        val owner = ClubChatOutbox("first-account")
        owner.open(member).pending.value = listOf(row)
        val otherAccount = clubChatOutboxSaver("second-account").restore(saved(owner))!!
        assertTrue(otherAccount.open(member).pending.value.isEmpty())
    }

    @Test fun revocationErasesTheOwnerAndLateCallbacksCannotReviveIt() {
        val owner = ClubChatOutbox("self")
        val previous = owner.open(member)
        previous.pending.value = listOf(row)
        val previousEpoch = owner.proofEpoch
        owner.clear()
        assertTrue(owner.proofEpoch > previousEpoch)
        previous.pending.value = listOf(row.copy(accepted = true))
        assertNull(owner.current)
        val restored = clubChatOutboxSaver("self").restore(saved(owner))!!
        assertTrue(restored.open(member).pending.value.isEmpty())
    }

    @Test fun saveReadsTheLatestRoomStateWithoutWaitingForAUiCollector() {
        val owner = ClubChatOutbox("self")
        val room = owner.open(member)
        room.pending.value = listOf(row)
        room.pending.value = emptyList()
        assertTrue(clubChatOutboxSaver("self").restore(saved(owner))!!.open(member).pending.value.isEmpty())
    }
}
