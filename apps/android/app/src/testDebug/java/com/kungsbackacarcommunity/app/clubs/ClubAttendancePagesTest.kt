package com.kungsbackacarcommunity.app.clubs

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Headless Compose regressions; the parent runs these centrally without an emulator. */
@RunWith(AndroidJUnit4::class)
class ClubAttendancePagesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(repo: AttendanceRepository) {
        compose.setContent {
            KccTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ClubEventAttendanceSection("viewer", "event", repo)
                }
            }
        }
    }

    private fun more() {
        compose.onNodeWithText(compose.activity.getString(R.string.clubs_more))
            .performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun loadedPagesObserveWithdrawalsAndRenamesWithoutFirstPageChanges() {
        val repo = AttendanceRepository()
        show(repo)
        more()
        compose.onNodeWithText("Club 040").assertExists()
        compose.runOnIdle {
            val page = repo.observations.single()
            assertEquals("event", page.eventId)
            assertEquals((26..50).map(::clubId), page.ids)
            assertEquals(25, page.ids.size)
            repo.live.getValue(page).value = attendanceClubs(26..50)
                .filterNot { it.id == clubId(40) }
                .map { if (it.id == clubId(41)) it.copy(name = "Updated club") else it }
        }
        compose.onNodeWithText("Club 040").assertDoesNotExist()
        compose.onNodeWithText("Club 041").assertDoesNotExist()
        compose.onNodeWithText("Updated club").assertExists()
        // Updating a name in the first page must not reset later page subscriptions.
        compose.runOnIdle {
            repo.first.value = repo.first.value.map { if (it.id == clubId(1)) it.copy(name = "First renamed") else it }
        }
        compose.onNodeWithText("Updated club").assertExists()
        compose.runOnIdle { assertEquals(1, repo.observations.size); assertEquals(1, repo.active.size) }
    }

    @Test fun emptyLivePageKeepsFetchedCursorAndRawExhaustion() {
        val repo = AttendanceRepository()
        repo.results[clubId(50)] = attendanceClubs(51..51)
        show(repo)
        more()
        compose.runOnIdle { repo.live.getValue(repo.observations.single()).value = emptyList() }
        compose.onNodeWithText("Club 050").assertDoesNotExist()
        more()
        compose.onNodeWithText("Club 051").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.clubs_more)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(clubId(25), clubId(50)), repo.afterIds)
            assertEquals(listOf(25, 1), repo.observations.map { it.ids.size })
            assertTrue(repo.observations.all { it.ids.size in 1..25 && it.ids.distinct() == it.ids })
        }
    }

    @Test fun readinessClosureDisposesPagesAndCancelsLatePaginationBeforeReattaching() {
        val repo = AttendanceRepository()
        val late = CompletableDeferred<List<Club>>()
        repo.delayed[clubId(50)] = late
        show(repo)
        more()
        more()
        compose.runOnIdle { repo.ready.value = false }
        compose.onNodeWithText("Club 026").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(repo.active.isEmpty())
            assertEquals(0, repo.firstReaders)
            assertEquals(listOf(clubId(50)), repo.cancelled)
            late.complete(attendanceClubs(51..60))
            repo.ready.value = true
        }
        compose.onNodeWithText("Club 001").assertExists()
        compose.onNodeWithText("Club 026").assertDoesNotExist()
        compose.onNodeWithText("Club 051").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, repo.firstReaders); assertTrue(repo.active.isEmpty()) }
        more()
        compose.onNodeWithText("Club 026").assertExists()
        compose.runOnIdle { assertEquals(listOf(clubId(25), clubId(50), clubId(25)), repo.afterIds) }
    }

    @Test fun accountChangeDisposesExplicitIdObserversAndResetsCursor() {
        val repo = AttendanceRepository()
        val uid = mutableStateOf("first-viewer")
        compose.setContent {
            KccTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ClubEventAttendanceSection(uid.value, "event", repo)
                }
            }
        }
        more()
        compose.onNodeWithText("Club 026").assertExists()
        compose.runOnIdle { uid.value = "second-viewer" }
        compose.onNodeWithText("Club 026").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.active.isEmpty()); assertEquals(1, repo.firstReaders) }
        more()
        compose.runOnIdle { assertEquals(listOf(clubId(25), clubId(25)), repo.afterIds) }
    }

    @Test fun firstPageBoundaryChangeCancelsOldPageAndUsesNewRawBoundary() {
        val repo = AttendanceRepository()
        val late = CompletableDeferred<List<Club>>()
        repo.delayed[clubId(25)] = late
        repo.results[clubId(26)] = attendanceClubs(27..51)
        show(repo)
        more()
        compose.runOnIdle { repo.first.value = attendanceClubs(2..26) }
        compose.onNodeWithText("Club 001").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(clubId(25)), repo.cancelled)
            late.complete(attendanceClubs(26..50))
        }
        compose.onNodeWithText("Club 050").assertDoesNotExist()
        more()
        compose.onNodeWithText("Club 051").assertExists()
        compose.runOnIdle {
            assertEquals(listOf(clubId(25), clubId(26)), repo.afterIds)
            assertEquals((27..51).map(::clubId), repo.observations.single().ids)
        }
    }
}

private fun clubId(number: Int) = "club_${number.toString().padStart(3, '0')}"
private fun attendanceClubs(numbers: IntRange) = numbers.map { number ->
    Club(clubId(number), "Club ${number.toString().padStart(3, '0')}", "", "", "open", "owner", null, 1, 0, ClubStats())
}

private data class AttendanceObservation(val eventId: String, val ids: List<String>)

private class AttendanceRepository : ClubRepository {
    val ready = MutableStateFlow(true)
    val first = MutableStateFlow(attendanceClubs(1..25))
    val results = mutableMapOf(clubId(25) to attendanceClubs(26..50))
    val delayed = mutableMapOf<String, CompletableDeferred<List<Club>>>()
    val afterIds = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val observations = mutableListOf<AttendanceObservation>()
    val active = mutableSetOf<AttendanceObservation>()
    val live = mutableMapOf<AttendanceObservation, MutableStateFlow<List<Club>>>()
    private val fetched = mutableMapOf<String, Club>()
    var firstReaders = 0

    override fun observeEnabled(): Flow<Boolean> = ready
    override fun observePublicAttendanceReady(): Flow<Boolean> = ready
    override fun observeAccess(): Flow<ClubAccess> = flowOf(ClubAccess.None)
    override fun eventParticipants(eventId: String): Flow<List<Club>> = flow {
        firstReaders++
        try { emitAll(first) } finally { firstReaders-- }
    }
    override suspend fun moreEventParticipants(eventId: String, afterId: String): List<Club> {
        afterIds += afterId
        return try {
            (delayed[afterId]?.await() ?: results.getValue(afterId)).also { rows ->
                rows.forEach { fetched[it.id] = it }
            }
        } catch (e: CancellationException) { cancelled += afterId; throw e }
    }
    override fun observeEventParticipants(eventId: String, clubIds: List<String>): Flow<List<Club>> {
        require(clubIds.size in 1..25)
        val page = AttendanceObservation(eventId, clubIds.toList())
        observations += page
        val rows = live.getOrPut(page) { MutableStateFlow(clubIds.mapNotNull(fetched::get)) }
        return flow {
            active += page
            try { emitAll(rows) } finally { active -= page }
        }
    }
    override fun eventAttendance(eventId: String, clubId: String): Flow<Boolean?> = flowOf(null)
    override suspend fun directory(cursor: String?): ClubPage<Club> = error("unused")
    override suspend fun state(): ClubState = error("unused")
    override suspend fun get(clubId: String): Club = error("unused")
    override suspend fun members(clubId: String, cursor: String?): ClubPage<ClubMember> = error("unused")
    override suspend fun manage(action: String, fields: Map<String, Any?>, operationId: String): Map<String, Any?> = error("unused")
    override suspend fun uploadLogo(clubId: String, bytes: ByteArray): String = error("unused")
    override fun chat(): Flow<ClubChatState> = error("unused")
    override suspend fun olderMessages(clubId: String, before: ClubMessageCursor): ClubMessagePage = error("unused")
    override fun observeMessageRows(clubId: String, messageIds: List<String>): Flow<List<ClubMessage>> = error("unused")
    override suspend fun send(clubId: String, text: String, operationId: String) = error("unused")
    override suspend fun events(clubId: String, cursor: String?): ClubPage<ClubEvent> = error("unused")
    override suspend fun setEventAttendance(clubId: String, eventId: String, attending: Boolean, operationId: String) = error("unused")
    override suspend fun blockAuthor(uid: String) = error("unused")
}
