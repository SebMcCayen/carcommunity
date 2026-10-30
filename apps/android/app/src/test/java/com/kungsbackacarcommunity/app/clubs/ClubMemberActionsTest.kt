package com.kungsbackacarcommunity.app.clubs

import org.junit.Assert.*
import org.junit.Test

class ClubMemberActionsTest {
    private val member = ClubMember("target", "Member", ClubRole.Member)
    private val moderator = member.copy(role = ClubRole.Moderator)

    @Test fun ordinaryMembersAndSelfNeverOfferManagement() {
        for (role in ClubRole.entries) {
            assertTrue(clubMemberActions("target", role, member).isEmpty())
            assertTrue(clubMemberActions("viewer", ClubRole.Member, member.copy(role = role)).isEmpty())
            assertTrue(clubMemberActions("viewer", role, member.copy(role = ClubRole.Owner)).isEmpty())
        }
    }

    @Test fun moderatorOnlyRemovesOtherOrdinaryMembers() {
        assertEquals(listOf(ClubMemberAction.RemoveMember), clubMemberActions("viewer", ClubRole.Moderator, member))
        assertTrue(clubMemberActions("viewer", ClubRole.Moderator, moderator).isEmpty())
    }

    @Test fun ownerPromotesDemotesTransfersAndRemovesWithExistingWireContracts() {
        assertEquals(listOf(ClubMemberAction.PromoteModerator, ClubMemberAction.TransferOwnership, ClubMemberAction.RemoveMember),
            clubMemberActions("viewer", ClubRole.Owner, member))
        assertEquals(ClubMemberAction.DemoteModerator, clubMemberActions("viewer", ClubRole.Owner, moderator).first())
        assertEquals("moderator", ClubMemberAction.PromoteModerator.operation)
        assertEquals(mapOf("targetUid" to "target", "enabled" to true), ClubMemberAction.PromoteModerator.fields("target"))
        assertEquals(mapOf("targetUid" to "target", "enabled" to false), ClubMemberAction.DemoteModerator.fields("target"))
        assertEquals("transfer", ClubMemberAction.TransferOwnership.operation)
        assertEquals(mapOf("targetUid" to "target"), ClubMemberAction.RemoveMember.fields("target"))
    }

    @Test fun threeModeratorLimitDisablesPromotionWithoutBlockingDemotionOrRemoval() {
        assertTrue(ClubMemberAction.PromoteModerator.enabled(2, false))
        assertFalse(ClubMemberAction.PromoteModerator.enabled(3, false))
        assertFalse(ClubMemberAction.PromoteModerator.enabled(4, false))
        assertTrue(ClubMemberAction.DemoteModerator.enabled(3, false))
        assertTrue(ClubMemberAction.RemoveMember.enabled(3, false))
        for (action in ClubMemberAction.entries) assertFalse(action.enabled(0, true))
    }
}
