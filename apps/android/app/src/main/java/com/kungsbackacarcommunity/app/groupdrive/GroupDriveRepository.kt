package com.kungsbackacarcommunity.app.groupdrive

import kotlinx.coroutines.flow.Flow

/**
 * Group-driving operations (Phase 12 slice 11). Firebase-free interface so the
 * route/screen logic is unit- and UI-testable with fakes. Roster reads are
 * rules-gated; all writes go through the groupDrive.* callables.
 */
interface GroupDriveRepository {
    fun observeParticipants(eventId: String): Flow<List<GroupDriveParticipant>>

    fun observeMyStatus(eventId: String, uid: String): Flow<GroupDriveStatus?>

    suspend fun join(eventId: String)

    suspend fun updateStatus(eventId: String, status: GroupDriveStatus)

    suspend fun leave(eventId: String)
}
