package com.kungsbackacarcommunity.app.clubs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClubEventAttendanceStateTest {
    private fun member(role: ClubRole, clubId: String = "club", epoch: String = "first") =
        ClubAccess.Member(ClubMembership(clubId, role, epoch))

    @Test fun `owner and moderator read private missing intent and follow registration and withdrawal`() = runTest {
        for (role in listOf(ClubRole.Owner, ClubRole.Moderator)) {
            val intent = MutableStateFlow<Boolean?>(false)
            val paths = mutableListOf<String>()
            val states = mutableListOf<ClubEventAttendanceState>()
            val job = backgroundScope.launch {
                managedClubEventAttendance("new-event", "club", flowOf(true), flowOf(member(role))) { path ->
                    paths += path
                    // Public summary GET denies absence because its rule requires resource.data.
                    // The private calendar rule permits an absent intent for current members.
                    check(path == "driverClubs/club/events/new-event") { "Missing public summary is not readable" }
                    intent
                }.attendanceStates().toList(states)
            }
            runCurrent()
            assertEquals(listOf("driverClubs/club/events/new-event"), paths)
            assertEquals(ClubEventAttendanceState.Ready(false), states.last())
            intent.value = true
            runCurrent()
            assertEquals(ClubEventAttendanceState.Ready(true), states.last())
            intent.value = false
            runCurrent()
            assertEquals(ClubEventAttendanceState.Ready(false), states.last())
            job.cancel()
            runCurrent()
        }
    }

    @Test fun `unverified access ordinary member no club wrong club and readiness closure never attach private reader`() = runTest {
        for (access in listOf(ClubAccess.Unverified, ClubAccess.None, member(ClubRole.Member), member(ClubRole.Owner, "other"))) {
            var reads = 0
            val values = managedClubEventAttendance("event", "club", flowOf(true), flowOf(access)) {
                reads++
                flowOf(true)
            }.toList()
            assertEquals(listOf<Boolean?>(null), values)
            assertEquals(0, reads)
        }
        var reads = 0
        val values = managedClubEventAttendance("event", "club", flowOf(false), flowOf(member(ClubRole.Owner))) {
            reads++
            flowOf(true)
        }.toList()
        assertEquals(listOf<Boolean?>(null), values)
        assertEquals(0, reads)
    }

    @Test fun `revocation detaches private listener and membership epoch needs fresh proof`() = runTest {
        val access = MutableStateFlow<ClubAccess>(member(ClubRole.Owner))
        val first = MutableStateFlow<Boolean?>(true)
        val rejoined = MutableStateFlow<Boolean?>(null)
        var reads = 0
        var active = 0
        val values = mutableListOf<Boolean?>()
        backgroundScope.launch {
            managedClubEventAttendance("event", "club", flowOf(true), access) {
                val source = if (reads++ == 0) first else rejoined
                flow { active++; try { emitAll(source) } finally { active-- } }
            }.toList(values)
        }
        runCurrent()
        assertEquals(true, values.last())
        access.value = ClubAccess.None
        runCurrent()
        assertNull(values.last())
        assertEquals(0, active)
        first.value = false
        runCurrent()
        assertNull(values.last())
        access.value = member(ClubRole.Owner, epoch = "rejoined")
        runCurrent()
        assertEquals(2, reads)
        assertNull(values.last())
        rejoined.value = false
        runCurrent()
        assertEquals(false, values.last())
        // Even a same-club membership epoch change must replace the observer.
        access.value = member(ClubRole.Moderator, epoch = "third")
        runCurrent()
        assertEquals(3, reads)
        assertEquals(1, active)
    }

    @Test fun `readiness and role revocation clear a previously verified state before reopening`() = runTest {
        val ready = MutableStateFlow(true)
        val access = MutableStateFlow<ClubAccess>(member(ClubRole.Moderator))
        val intent = MutableStateFlow<Boolean?>(true)
        var active = 0
        val values = mutableListOf<Boolean?>()
        backgroundScope.launch {
            managedClubEventAttendance("event", "club", ready, access) {
                flow { active++; try { emitAll(intent) } finally { active-- } }
            }.toList(values)
        }
        runCurrent()
        ready.value = false
        runCurrent()
        assertNull(values.last())
        assertEquals(0, active)
        intent.value = null
        ready.value = true
        runCurrent()
        assertNull(values.last())
        assertEquals(1, active)
        intent.value = false
        runCurrent()
        assertEquals(false, values.last())
        access.value = member(ClubRole.Member)
        runCurrent()
        assertNull(values.last())
        assertEquals(0, active)
    }

    @Test fun `cache and unverified emissions show unavailable and never preserve earlier true or false`() = runTest {
        val states = flowOf(null, false, null, true, null).attendanceStates().toList()
        assertEquals(ClubEventAttendanceState.Loading, states.first())
        assertTrue(states[1] is ClubEventAttendanceState.Unavailable)
        assertEquals(ClubEventAttendanceState.Ready(false), states[2])
        assertTrue(states[3] is ClubEventAttendanceState.Unavailable)
        assertEquals(ClubEventAttendanceState.Ready(true), states[4])
        assertTrue(states[5] is ClubEventAttendanceState.Unavailable)
    }

    @Test fun `permission and transport errors become explicit unavailable and recollection can retry`() = runTest {
        for (failure in listOf(ClubException("permission-denied"), ClubException("unavailable"))) {
            var attempts = 0
            val source = managedClubEventAttendance("event", "club", flowOf(true), flowOf(member(ClubRole.Owner))) {
                flow { if (attempts++ == 0) throw failure else emit(false) }
            }.attendanceStates()
            val failed = source.toList().last() as ClubEventAttendanceState.Unavailable
            assertSame(failure, failed.error)
            assertEquals(ClubEventAttendanceState.Ready(false), source.toList().last())
            assertEquals(2, attempts)
        }
    }

    @Test fun `cancellation never becomes a retryable failure`() = runTest {
        val cancelled = CancellationException("membership lost")
        try {
            flow<Boolean?> { throw cancelled }.attendanceStates().toList()
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
    }
}
