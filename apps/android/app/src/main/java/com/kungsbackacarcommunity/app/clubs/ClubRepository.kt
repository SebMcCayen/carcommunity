package com.kungsbackacarcommunity.app.clubs

import kotlinx.coroutines.flow.Flow

/** Shared Driver callables own authorization. No client writes to club documents. */
interface ClubRepository {
    fun observeEnabled(): Flow<Boolean>
    fun observePublicAttendanceReady(): Flow<Boolean>
    fun observeAccess(): Flow<ClubAccess>
    /** Owner-only revision; null means no fresh server proof. */
    fun observeInvitationsRevision(): Flow<Long?> = kotlinx.coroutines.flow.flowOf(0L)
    suspend fun directory(cursor: String? = null): ClubPage<Club>
    suspend fun state(): ClubState
    suspend fun get(clubId: String): Club
    suspend fun members(clubId: String, cursor: String? = null): ClubPage<ClubMember>
    suspend fun manage(action: String, fields: Map<String, Any?> = emptyMap(), operationId: String): Map<String, Any?>
    suspend fun uploadLogo(clubId: String, bytes: ByteArray): String
    fun chat(): Flow<ClubChatState>
    suspend fun olderMessages(clubId: String, before: ClubMessageCursor): ClubMessagePage
    fun observeMessageRows(clubId: String, messageIds: List<String>): Flow<List<ClubMessage>>
    suspend fun send(clubId: String, text: String, operationId: String)
    suspend fun events(clubId: String, cursor: String? = null): ClubPage<ClubEvent>
    fun eventParticipants(eventId: String): Flow<List<Club>>
    fun observeEventParticipants(eventId: String, clubIds: List<String>): Flow<List<Club>>
    fun eventAttendance(eventId: String, clubId: String): Flow<Boolean?>
    suspend fun moreEventParticipants(eventId: String, afterId: String): List<Club>
    suspend fun setEventAttendance(clubId: String, eventId: String, attending: Boolean, operationId: String)
    suspend fun blockAuthor(uid: String)
}
