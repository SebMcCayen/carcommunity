package com.kungsbackacarcommunity.app.groupdrive

import com.kungsbackacarcommunity.app.events.EventStatus
import com.kungsbackacarcommunity.app.events.RsvpStatus

/**
 * Group driving domain (Phase 12 slice 11).
 *
 * Mirrors the backend groupdrive-core contract: the participation status
 * vocabulary (joined/on_the_way/arrived/left — `left` is set only by
 * groupDrive.leave, never updateStatus) and the roster shape. Pure Kotlin —
 * JVM-testable. Live-location sharing is a SEPARATE opt-in (never implied by
 * joining a drive).
 */
enum class GroupDriveStatus(val wire: String) {
    JOINED("joined"),
    ON_THE_WAY("on_the_way"),
    ARRIVED("arrived"),
    LEFT("left"),
    ;

    companion object {
        fun fromWire(value: String?): GroupDriveStatus? = values().firstOrNull { it.wire == value }

        /** Statuses a participant can set via updateStatus (never `left`). */
        val UPDATABLE: List<GroupDriveStatus> = listOf(JOINED, ON_THE_WAY, ARRIVED)
    }
}

/** A roster entry (events/{id}/groupDriveParticipants/{uid}). */
data class GroupDriveParticipant(
    val uid: String,
    val displayName: String?,
    val status: GroupDriveStatus,
)

object GroupDrive {
    /**
     * Joining requires passing the member gate, a published event, and a
     * going/maybe RSVP — mirrors the backend join precondition and the roster
     * read rule. The gate is switchable: while member gating is disabled it
     * admits any signed-in, non-suspended user, and the backend agrees
     * (groupDrive.join's requireMemberActor resolves the same way).
     */
    fun canJoin(passesMemberGate: Boolean, eventStatus: EventStatus?, rsvp: RsvpStatus?): Boolean =
        passesMemberGate &&
            eventStatus == EventStatus.PUBLISHED &&
            (rsvp == RsvpStatus.GOING || rsvp == RsvpStatus.MAYBE)

    /** Whether the caller currently participates (joined and not left). */
    fun isParticipating(status: GroupDriveStatus?): Boolean =
        status != null && status != GroupDriveStatus.LEFT

    /** Active roster (excludes participants who have left). */
    fun activeParticipants(roster: List<GroupDriveParticipant>): List<GroupDriveParticipant> =
        roster.filter { it.status != GroupDriveStatus.LEFT }
}
