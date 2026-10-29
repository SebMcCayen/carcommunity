package com.kungsbackacarcommunity.app.clubs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

internal sealed interface ClubEventAttendanceState {
    data object Loading : ClubEventAttendanceState
    data class Ready(val attending: Boolean) : ClubEventAttendanceState
    data class Unavailable(val error: Throwable) : ClubEventAttendanceState
}

internal fun Flow<Boolean?>.attendanceStates(): Flow<ClubEventAttendanceState> =
    map<Boolean?, ClubEventAttendanceState> { attending ->
        if (attending == null) ClubEventAttendanceState.Unavailable(ClubException("unavailable"))
        else ClubEventAttendanceState.Ready(attending)
    }.onStart { emit(ClubEventAttendanceState.Loading) }.catch { error ->
        if (error is CancellationException) throw error
        emit(ClubEventAttendanceState.Unavailable(error))
    }

/** Only the current, server-verified manager reads the private attendance intent. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun managedClubEventAttendance(
    eventId: String,
    clubId: String,
    readiness: Flow<Boolean>,
    access: Flow<ClubAccess>,
    observeDocument: (String) -> Flow<Boolean?>,
): Flow<Boolean?> = combine(readiness, access) { ready, current ->
    (current as? ClubAccess.Member)?.membership?.takeIf {
        ready && it.clubId == clubId && it.role.managesMembers
    }
}.distinctUntilChanged().flatMapLatest { membership ->
    if (membership == null) flowOf(null)
    else {
        // A public summary GET requires resource.data and denies a missing row.
        // The private calendar GET authorizes membership independently of existence,
        // so a server-confirmed missing intent means "not registered", including withdrawal.
        observeDocument("driverClubs/$clubId/events/$eventId").onStart { emit(null) }
    }
}.distinctUntilChanged()
