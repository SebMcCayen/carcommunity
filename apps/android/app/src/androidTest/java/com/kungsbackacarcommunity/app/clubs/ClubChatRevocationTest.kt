package com.kungsbackacarcommunity.app.clubs

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Security UX: membership loss must dispose history AND unsent drafts, not just disable send. */
class ClubChatRevocationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private val member = ClubMembership("first", ClubRole.Member, "2026-10-27T10:00:00Z")
    @Test fun revokedMembershipClearsHistoryAndDraftBeforeAnotherClubOpens() {
        val repo = FakeClubRepository(ClubChatState.Ready(member, listOf(ClubMessage("one", "friend", "Friend", "Private club message", 1L))))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithText("Private club message").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.clubs_message)).performTextInput("Unsent private draft")
        compose.runOnIdle { repo.messages.value = ClubChatState.Unavailable }
        compose.onNodeWithText("Private club message").assertDoesNotExist()
        compose.onNodeWithText("Unsent private draft").assertDoesNotExist()
        compose.runOnIdle { repo.messages.value = ClubChatState.Ready(member.copy(clubId = "second"), emptyList()) }
        compose.onNodeWithText("Unsent private draft").assertDoesNotExist()
        compose.onNodeWithText("Private club message").assertDoesNotExist()
    }
    @Test fun olderPagesTrackRemoteRemovalAndAdvancePastRedactedRows() {
        val start = ClubMessageCursor("recent", 100, 123)
        val next = ClubMessageCursor("old25", 50, 456)
        val repo = FakeClubRepository(ClubChatState.Ready(member,
            listOf(ClubMessage("recent", "friend", "Friend", "Recent", 100000L)), oldestCursor = start, hasOlder = true))
        repo.historical.value = listOf(ClubMessage("old1", "friend", "Friend", "Moderated remotely", 90000L))
        repo.pageResults = listOf(ClubMessagePage((1..25).map { "old$it" }, next, true), ClubMessagePage(emptyList(), null, false))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithText(str(R.string.clubs_older)).performClick()
        compose.onNodeWithText("Moderated remotely").assertExists()
        compose.runOnIdle { repo.historical.value = emptyList() }
        compose.onNodeWithText("Moderated remotely").assertDoesNotExist()
        // Query exhaustion comes from 25 raw rows, not the now-zero visible rows.
        compose.onNodeWithText(str(R.string.clubs_older)).performClick()
        compose.waitUntil { repo.cursorRequests.size == 2 }
        compose.runOnIdle { assertEquals(listOf(start, next), repo.cursorRequests) }
    }
    @Test fun loadedHistoryKeepsEveryDisplacedWindowLiveAndHonorsModeration() {
        fun message(i: Int) = ClubMessage("m$i", "author$i", "Member $i", "Message $i", i * 1000L)
        fun cursor(i: Int) = ClubMessageCursor("m$i", i.toLong(), i * 1_000_000 + 123)
        val all = (1..102).map(::message)
        val repo = FakeClubRepository(ClubChatState.Unavailable)
        repo.historical.value = all
        // The raw page ends on a redacted row. It must still advance the nanos cursor.
        repo.redactedIds.value = setOf("m26")
        repo.pageResults = listOf(
            ClubMessagePage((50 downTo 26).map { "m$it" }, cursor(26), true),
            ClubMessagePage((25 downTo 1).map { "m$it" }, cursor(1), true),
            ClubMessagePage(emptyList(), null, false),
        )
        var hidden = emptySet<String>()
        fun publishLatest(last: Int) {
            val raw = repo.historical.value.filter { it.createdAtMillis!! <= last * 1000L }
                .sortedByDescending { it.createdAtMillis }.take(25)
            val boundary = raw.last().createdAtMillis!! / 1000L
            repo.messages.value = ClubChatState.Ready(member,
                raw.filterNot { it.id in repo.redactedIds.value }, hidden, cursor(boundary.toInt()), raw.size == 25)
        }
        fun show(text: String) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        fun loadOlder() {
            show(str(R.string.clubs_older))
            compose.onNodeWithText(str(R.string.clubs_older)).performClick()
            compose.waitForIdle()
        }
        publishLatest(75)
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        loadOlder()
        loadOlder()
        // Each arrival gets its own settled window, so StateFlow cannot conflate them.
        // Only one partial query may exist beyond full batches, not one per arrival.
        for (last in 76..101) {
            compose.runOnIdle { publishLatest(last) }
            compose.runOnIdle {
                val retainedIdCount = 25 + (last - 75)
                val retainedQueryBound = (retainedIdCount + 24) / 25
                assertEquals("Active history + retained queries after arrival $last",
                    2 + retainedQueryBound, repo.activeRowQueryCount())
            }
        }
        for (i in 101 downTo 1) if (i != 26) show("Message $i")
        compose.runOnIdle {
            assertTrue(repo.rowRequests.all { it.size in 1..25 })
            assertTrue(repo.activeRowIds().containsAll((1..101).map { "m$it" }))
        }
        // Displaced rows must still react to remote redaction, block, and deletion.
        for (i in listOf(51, 76)) {
            show("Message $i")
            compose.runOnIdle { repo.redactedIds.value += "m$i" }
            compose.onNodeWithText("Message $i").assertDoesNotExist()
        }
        for (i in listOf(52, 77, 20)) {
            show("Message $i")
            compose.runOnIdle { hidden = hidden + "author$i"; publishLatest(101) }
            compose.onNodeWithText("Message $i").assertDoesNotExist()
        }
        for (i in listOf(53, 78, 21)) {
            show("Message $i")
            compose.runOnIdle {
                repo.historical.value = repo.historical.value.filterNot { it.id == "m$i" }
                publishLatest(101)
            }
            compose.onNodeWithText("Message $i").assertDoesNotExist()
        }
        compose.runOnIdle { publishLatest(102) }
        show("Message 102")
        loadOlder()
        compose.runOnIdle { assertEquals(listOf(cursor(51), cursor(26), cursor(1)), repo.cursorRequests) }
        compose.onNodeWithText(str(R.string.clubs_older)).assertDoesNotExist()
    }
    @Test fun arrivalsDuringPageLoadStayCoveredAndRevocationDisposesPinnedIds() {
        val initial = (1..25).map { ClubMessage("m$it", "friend", "Friend", "Message $it", it * 1000L) }
        val boundary = ClubMessageCursor("m1", 1, 987654321)
        val repo = FakeClubRepository(ClubChatState.Ready(member, initial.reversed(), oldestCursor = boundary, hasOlder = true))
        repo.historical.value = initial
        val gate = CompletableDeferred<Unit>()
        repo.pageGate = gate
        repo.pageResults = listOf(ClubMessagePage(listOf("older"), ClubMessageCursor("older", 0, 123), false))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(str(R.string.clubs_older)))
        compose.onNodeWithText(str(R.string.clubs_older)).performClick()
        compose.waitUntil { repo.cursorRequests.size == 1 }
        compose.runOnIdle {
            val arriving = ClubMessage("new", "friend", "Friend", "New arrival", 26000L)
            repo.historical.value += arriving
            repo.messages.value = ClubChatState.Ready(member, listOf(arriving) + initial.drop(1).reversed(),
                oldestCursor = ClubMessageCursor("m2", 2, 456), hasOlder = true)
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Message 1"))
        compose.onNodeWithText("Message 1").assertIsDisplayed()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.clubs_message)).performTextInput("Private paged draft")
        compose.runOnIdle {
            assertEquals(listOf(boundary), repo.cursorRequests)
            assertTrue(repo.activeRowIds().containsAll(initial.map { it.id } + "older" + "new"))
            repo.messages.value = ClubChatState.Unavailable
        }
        compose.onNodeWithText("Message 1").assertDoesNotExist()
        compose.onNodeWithText("Private paged draft").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.activeRowIds().isEmpty()) }
        // Even a new session for the same membership must not reuse private query IDs.
        compose.runOnIdle {
            repo.pageGate = null
            repo.pageResults = listOf(
                ClubMessagePage(emptyList(), null, false),
                ClubMessagePage(listOf("fresh-history"), ClubMessageCursor("fresh-history", 0, 999), false),
            )
            repo.messages.value = ClubChatState.Ready(member,
                listOf(ClubMessage("fresh", "friend", "Friend", "Fresh session", 100000L)),
                oldestCursor = ClubMessageCursor("fresh", 100, 111), hasOlder = true)
            repo.historical.value = initial.map { it.copy(text = "Late private snapshot") } +
                ClubMessage("fresh-history", "friend", "Friend", "Fresh history", 0L)
        }
        compose.onNodeWithText(str(R.string.clubs_older)).performClick()
        compose.onNodeWithText("Fresh history").assertIsDisplayed()
        compose.onNodeWithText("Late private snapshot").assertDoesNotExist()
        compose.onNodeWithText("Private paged draft").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(setOf("fresh", "fresh-history"), repo.activeRowIds())
            assertEquals(ClubMessageCursor("fresh", 100, 111), repo.cursorRequests.last())
        }
        // Background/re-entry must dispose the new session's pinned queries too,
        // even if the stopped activity does not draw another composition frame.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        repo.messages.value = ClubChatState.Unavailable
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Fresh history").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(repo.activeRowIds().isEmpty())
            repo.messages.value = ClubChatState.Ready(member, emptyList())
        }
        compose.onNodeWithText("Late private snapshot").assertDoesNotExist()
        compose.onNodeWithText("Private paged draft").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.activeRowIds().isEmpty()) }
    }
    @Test fun backgroundReturnRequiresFreshProofAndDiscardsDraft() {
        val repo = FakeClubRepository(ClubChatState.Ready(member, listOf(ClubMessage("one", "friend", "Friend", "Before background", 1L))))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithText(str(R.string.clubs_message)).performTextInput("Private unsent text")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        repo.messages.value = ClubChatState.Unavailable
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Before background").assertDoesNotExist()
        compose.onNodeWithText("Private unsent text").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.chatCalls >= 2) }
    }
    @Test fun createClubThenLeaveReturnsToDirectoryWithCooldown() {
        val repo = FakeClubRepository(ClubChatState.Unavailable)
        compose.setContent { KccTheme { SharedClubRoute("me", repository = repo) } }
        compose.onNodeWithText(str(R.string.clubs_create)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.clubs_name)).performTextInput("Västkustklubben")
        compose.onNodeWithText(str(R.string.clubs_description)).performTextInput("En klubb för alla bilintresserade")
        compose.onNodeWithText(str(R.string.clubs_location)).performTextInput("Kungsbacka")
        compose.onNodeWithText(str(R.string.clubs_save)).performClick()
        compose.onNodeWithText("Västkustklubben").assertExists()
        compose.onNodeWithText(str(R.string.clubs_manage)).assertExists()
        compose.runOnIdle {
            assertEquals("open", repo.createdPayload?.get("visibility"))
            assertEquals("Västkustklubben", repo.createdPayload?.get("name"))
        }
        compose.onNodeWithText(str(R.string.clubs_leave)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.clubs_confirm)).performClick()
        compose.onNodeWithText(str(R.string.clubs_create)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Västkustklubben").assertDoesNotExist()
    }
    @Test fun textAndEmojiAreSentWithoutTransformation() {
        val repo = FakeClubRepository(ClubChatState.Ready(member, emptyList()))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithText(str(R.string.clubs_message)).performTextInput("Hej klubben 🚗👋")
        compose.onNodeWithText(str(R.string.clubs_send)).performClick()
        compose.waitUntil { repo.sent != null }
        compose.runOnIdle { assertEquals("Hej klubben 🚗👋", repo.sent); assertTrue(repo.operation!!.length >= 16) }
    }
    @Test fun blockedAuthorsDisappearFromAlreadyLoadedMessages() {
        val message = ClubMessage("one", "hidden", "Hidden", "Blocked text", 1L)
        val repo = FakeClubRepository(ClubChatState.Ready(member, listOf(message)))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithText("Blocked text").assertIsDisplayed()
        compose.runOnIdle { repo.messages.value = ClubChatState.Ready(member, listOf(message), setOf("hidden")) }
        compose.onNodeWithText("Blocked text").assertDoesNotExist()
    }
}

private class FakeClubRepository(initial: ClubChatState) : ClubRepository {
    val messages = MutableStateFlow(initial)
    val historical = MutableStateFlow<List<ClubMessage>>(emptyList())
    val redactedIds = MutableStateFlow<Set<String>>(emptySet())
    val rowRequests = mutableListOf<List<String>>()
    private val activeRows = mutableListOf<List<String>>()
    fun activeRowIds() = activeRows.flatten().toSet()
    fun activeRowQueryCount() = activeRows.size
    var pageGate: CompletableDeferred<Unit>? = null
    var pageResults: List<ClubMessagePage> = emptyList()
    val cursorRequests = mutableListOf<ClubMessageCursor>()
    val access = MutableStateFlow<ClubAccess>(ClubAccess.None)
    var account = ClubState()
    var createdPayload: Map<String, Any?>? = null
    var sent: String? = null
    var operation: String? = null
    var chatCalls = 0
    override fun chat(): Flow<ClubChatState> { chatCalls++; return messages }
    override fun observeEnabled() = flowOf(true)
    override fun observePublicAttendanceReady() = flowOf(true)
    override fun observeAccess(): Flow<ClubAccess> = access
    override suspend fun directory(cursor: String?): ClubPage<Club> = ClubPage(emptyList(), null)
    override suspend fun state(): ClubState = account
    override suspend fun get(clubId: String): Club = error("unused")
    override suspend fun members(clubId: String, cursor: String?): ClubPage<ClubMember> = ClubPage(emptyList(), null)
    override suspend fun manage(action: String, fields: Map<String, Any?>, operationId: String): Map<String, Any?> {
        when (action) {
            "create" -> {
                createdPayload = fields
                val club = Club("new-club", fields["name"] as String, fields["description"] as String,
                    fields["location"] as String, fields["visibility"] as String, "me", null, 1, 0, ClubStats())
                val membership = ClubMembership(club.id, ClubRole.Owner, "2026-10-27T10:00:00Z")
                account = ClubState(club, membership)
                access.value = ClubAccess.Member(membership)
            }
            "leave" -> {
                account = ClubState(joinAllowedAt = java.time.Instant.now().plusSeconds(86400).toString())
                access.value = ClubAccess.None
            }
        }
        return mapOf("clubId" to "new-club")
    }
    override suspend fun uploadLogo(clubId: String, bytes: ByteArray): String = error("unused")
    override suspend fun olderMessages(clubId: String, before: ClubMessageCursor): ClubMessagePage {
        cursorRequests.add(before)
        pageGate?.await()
        return pageResults.getOrElse(cursorRequests.lastIndex) { ClubMessagePage(emptyList(), null, false) }
    }
    override fun observeMessageRows(clubId: String, messageIds: List<String>): Flow<List<ClubMessage>> = flow {
        require(messageIds.size in 1..25)
        rowRequests.add(messageIds)
        activeRows.add(messageIds)
        try {
            emitAll(combine(historical, redactedIds) { rows, redacted -> rows.filter { it.id in messageIds && it.id !in redacted } })
        } finally { activeRows.remove(messageIds) }
    }
    override suspend fun send(clubId: String, text: String, operationId: String) { sent = text; operation = operationId }
    override suspend fun events(clubId: String, cursor: String?): ClubPage<ClubEvent> = ClubPage(emptyList(), null)
    override fun eventParticipants(eventId: String): Flow<List<Club>> = flowOf(emptyList())
    override fun observeEventParticipants(eventId: String, clubIds: List<String>): Flow<List<Club>> = flowOf(emptyList())
    override fun eventAttendance(eventId: String, clubId: String): Flow<Boolean?> = flowOf(false)
    override suspend fun moreEventParticipants(eventId: String, afterId: String): List<Club> = emptyList()
    override suspend fun setEventAttendance(clubId: String, eventId: String, attending: Boolean, operationId: String) = Unit
    override suspend fun blockAuthor(uid: String) = Unit
}
