package com.kungsbackacarcommunity.app.clubs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    var sent: String? = null
    var operation: String? = null
    override fun chat(): Flow<ClubChatState> = messages
    override fun observeEnabled() = flowOf(true)
    override fun observeAccess(): Flow<ClubAccess> = flowOf(ClubAccess.None)
    override suspend fun directory(cursor: String?): ClubPage<Club> = error("unused")
    override suspend fun state(): ClubState = error("unused")
    override suspend fun get(clubId: String): Club = error("unused")
    override suspend fun members(clubId: String, cursor: String?): ClubPage<ClubMember> = error("unused")
    override suspend fun manage(action: String, fields: Map<String, Any?>, operationId: String): Map<String, Any?> = error("unused")
    override suspend fun uploadLogo(clubId: String, bytes: ByteArray): String = error("unused")
    override suspend fun olderMessages(clubId: String, before: ClubMessage) = emptyList<ClubMessage>()
    override suspend fun send(clubId: String, text: String, operationId: String) { sent = text; operation = operationId }
    override suspend fun events(clubId: String, cursor: String?): ClubPage<ClubEvent> = error("unused")
    override fun eventParticipants(eventId: String): Flow<List<Club>> = flowOf(emptyList())
    override fun eventAttendance(eventId: String, clubId: String): Flow<Boolean?> = flowOf(false)
    override suspend fun moreEventParticipants(eventId: String, afterId: String): List<Club> = emptyList()
    override suspend fun setEventAttendance(clubId: String, eventId: String, attending: Boolean, operationId: String) = Unit
    override suspend fun blockAuthor(uid: String) = Unit
}
