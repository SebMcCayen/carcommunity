package com.kungsbackacarcommunity.app.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.clubs.*
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.profile.LiveProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Club uses its existing repository acknowledgement, not the shared callable composer. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class ClubSentDraftControlTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clubSuccessClearsDraftWithoutChangingTheRoute() {
        val repo = start()
        send().performClick()
        awaitAttempts(repo, 1)
        draft(TEXT)
        input().assertIsNotEnabled() // Club deliberately locks the input during send.
        compose.runOnIdle { repo.attempts.single().ack.complete(true) }
        draft("")
        input().performTextInput(TEXT)
        send().performClick()
        awaitAttempts(repo, 2)
        compose.runOnIdle { assertNotEquals(repo.attempts[0].id, repo.attempts[1].id) }
    }

    @Test fun clubFailureKeepsDraftAndOperationUntilSuccess() {
        val repo = start()
        send().performClick()
        awaitAttempts(repo, 1)
        compose.runOnIdle { repo.attempts.single().ack.complete(false) }
        draft(TEXT)
        send().assertIsEnabled()
        send().performClick()
        awaitAttempts(repo, 2)
        compose.runOnIdle {
            assertEquals(repo.attempts[0].id, repo.attempts[1].id)
            assertEquals(listOf(TEXT, TEXT), repo.attempts.map { it.text })
            repo.attempts[1].ack.complete(true)
        }
        draft("")
    }

    private fun start(): Repository {
        val repo = Repository()
        val cooldown = ChatSendCooldown()
        compose.setContent { KccTheme {
            ClubChatRoute("club-draft-self", repo, LiveProfileRepository.EMPTY, cooldown)
        } }
        input().performTextInput(TEXT)
        return repo
    }
    private fun input() = compose.onNodeWithTag("club-chat-input")
    private fun draft(text: String) {
        assertEquals(text, input().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    private fun send() = compose.onNodeWithText(
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.channel_send),
    )
    private fun awaitAttempts(repo: Repository, count: Int) = compose.waitUntil(5_000) {
        compose.runOnIdle { repo.attempts.size == count }
    }
    private data class Attempt(val text: String, val id: String, val ack: CompletableDeferred<Boolean> = CompletableDeferred())
    private class Repository : ClubRepository {
        val attempts = mutableListOf<Attempt>()
        private val member = ClubMembership("draft-club", ClubRole.Member, "2026-10-30T00:00:00Z")
        override fun chat() = flowOf(ClubChatState.Ready(member, emptyList()))
        override suspend fun send(clubId: String, text: String, operationId: String) {
            assertEquals(member.clubId, clubId)
            val attempt = Attempt(text, operationId)
            attempts += attempt
            if (!attempt.ack.await()) throw ClubException("unavailable")
        }
        override fun observeEnabled() = flowOf(true)
        override fun observePublicAttendanceReady() = flowOf(true)
        override fun observeAccess() = flowOf<ClubAccess>(ClubAccess.Member(member))
        override suspend fun directory(cursor: String?) = ClubPage<Club>(emptyList(), null)
        override suspend fun state() = ClubState(membership = member)
        override suspend fun get(clubId: String): Club = error("Not used by chat")
        override suspend fun members(clubId: String, cursor: String?) = ClubPage<ClubMember>(emptyList(), null)
        override suspend fun manage(action: String, fields: Map<String, Any?>, operationId: String) = emptyMap<String, Any?>()
        override suspend fun uploadLogo(clubId: String, bytes: ByteArray): String = error("Not used by chat")
        override suspend fun olderMessages(clubId: String, before: ClubMessageCursor) = ClubMessagePage(emptyList(), null, false)
        override fun observeMessageRows(clubId: String, messageIds: List<String>) = flowOf(emptyList<ClubMessage>())
        override suspend fun events(clubId: String, cursor: String?) = ClubPage<ClubEvent>(emptyList(), null)
        override fun eventParticipants(eventId: String) = flowOf(emptyList<Club>())
        override fun observeEventParticipants(eventId: String, clubIds: List<String>) = flowOf(emptyList<Club>())
        override fun eventAttendance(eventId: String, clubId: String) = flowOf<Boolean?>(false)
        override suspend fun moreEventParticipants(eventId: String, afterId: String) = emptyList<Club>()
        override suspend fun setEventAttendance(clubId: String, eventId: String, attending: Boolean, operationId: String) = Unit
        override suspend fun blockAuthor(uid: String) = Unit
    }
    private companion object { const val TEXT = "Club draft 🚗" }
}
