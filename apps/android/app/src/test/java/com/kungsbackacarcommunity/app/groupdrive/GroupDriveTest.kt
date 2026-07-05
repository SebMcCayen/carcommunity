package com.kungsbackacarcommunity.app.groupdrive

import com.kungsbackacarcommunity.app.events.EventStatus
import com.kungsbackacarcommunity.app.events.RsvpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupDriveTest {

    @Test
    fun `status parses wire values and updatable excludes left`() {
        assertEquals(GroupDriveStatus.ON_THE_WAY, GroupDriveStatus.fromWire("on_the_way"))
        assertEquals(GroupDriveStatus.LEFT, GroupDriveStatus.fromWire("left"))
        assertNull(GroupDriveStatus.fromWire("driving"))
        assertEquals(
            listOf(GroupDriveStatus.JOINED, GroupDriveStatus.ON_THE_WAY, GroupDriveStatus.ARRIVED),
            GroupDriveStatus.UPDATABLE,
        )
        assertFalse(GroupDriveStatus.UPDATABLE.contains(GroupDriveStatus.LEFT))
    }

    @Test
    fun `canJoin requires member, published and going or maybe`() {
        assertTrue(GroupDrive.canJoin(true, EventStatus.PUBLISHED, RsvpStatus.GOING))
        assertTrue(GroupDrive.canJoin(true, EventStatus.PUBLISHED, RsvpStatus.MAYBE))
        assertFalse(GroupDrive.canJoin(true, EventStatus.PUBLISHED, RsvpStatus.NOT_GOING))
        assertFalse(GroupDrive.canJoin(false, EventStatus.PUBLISHED, RsvpStatus.GOING))
        assertFalse(GroupDrive.canJoin(true, EventStatus.CANCELLED, RsvpStatus.GOING))
    }

    @Test
    fun `isParticipating is false for left or null`() {
        assertTrue(GroupDrive.isParticipating(GroupDriveStatus.JOINED))
        assertTrue(GroupDrive.isParticipating(GroupDriveStatus.ARRIVED))
        assertFalse(GroupDrive.isParticipating(GroupDriveStatus.LEFT))
        assertFalse(GroupDrive.isParticipating(null))
    }

    @Test
    fun `activeParticipants excludes those who left`() {
        val roster =
            listOf(
                GroupDriveParticipant("a", "Ada", GroupDriveStatus.JOINED),
                GroupDriveParticipant("b", "Bo", GroupDriveStatus.LEFT),
                GroupDriveParticipant("c", "Cy", GroupDriveStatus.ARRIVED),
            )
        assertEquals(listOf("a", "c"), GroupDrive.activeParticipants(roster).map { it.uid })
    }
}
