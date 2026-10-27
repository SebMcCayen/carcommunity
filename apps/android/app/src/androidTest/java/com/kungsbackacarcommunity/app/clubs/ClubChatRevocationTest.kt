package com.kungsbackacarcommunity.app.clubs

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    var pageResults: List<ClubMessagePage> = emptyList()
    val cursorRequests = mutableListOf<ClubMessageCursor>()
    var sent: String? = null
    var operation: String? = null
    var chatCalls = 0
    override fun chat(): Flow<ClubChatState> { chatCalls++; return messages }
    override fun observeEnabled() = flowOf(true)
    override fun observePublicAttendanceReady() = flowOf(true)
    override fun observeAccess(): Flow<ClubAccess> = flowOf(ClubAccess.None)
    override suspend fun directory(cursor: String?): ClubPage<Club> = error("unused")
    override suspend fun state(): ClubState = error("unused")
    override suspend fun get(clubId: String): Club = error("unused")
    override suspend fun members(clubId: String, cursor: String?): ClubPage<ClubMember> = error("unused")
    override suspend fun manage(action: String, fields: Map<String, Any?>, operationId: String): Map<String, Any?> = error("unused")
    override suspend fun uploadLogo(clubId: String, bytes: ByteArray): String = error("unused")
    override suspend fun olderMessages(clubId: String, before: ClubMessageCursor): ClubMessagePage {
        cursorRequests.add(before)
        return pageResults.getOrElse(cursorRequests.lastIndex) { ClubMessagePage(emptyList(), null, false) }
    }
    override fun observeMessageRows(clubId: String, messageIds: List<String>): Flow<List<ClubMessage>> = historical.map { rows -> rows.filter { it.id in messageIds } }
    override suspend fun send(clubId: String, text: String, operationId: String) { sent = text; operation = operationId }
    override suspend fun events(clubId: String, cursor: String?): ClubPage<ClubEvent> = error("unused")
    override fun eventParticipants(eventId: String): Flow<List<Club>> = flowOf(emptyList())
    override fun observeEventParticipants(eventId: String, clubIds: List<String>): Flow<List<Club>> = flowOf(emptyList())
    override fun eventAttendance(eventId: String, clubId: String): Flow<Boolean?> = flowOf(false)
    override suspend fun moreEventParticipants(eventId: String, afterId: String): List<Club> = emptyList()
    override suspend fun setEventAttendance(clubId: String, eventId: String, attending: Boolean, operationId: String) = Unit
    override suspend fun blockAuthor(uid: String) = Unit
}
