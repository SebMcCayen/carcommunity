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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.kungsbackacarcommunity.app.design.chatQuickEmojiTestTag
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

    @Test fun clubClearsImmediatelyAndKeepsNewDraftThroughSuccessAndEcho() {
        val repo = start()
        send().performClick()
        awaitAttempts(repo, 1)
        draft("")
        input().assertIsEnabled().performTextInput("Next draft")
        send().assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.channel_statusSending)).assertIsDisplayed()
        compose.runOnIdle { repo.echo(0) }
        compose.onNodeWithText(str(R.string.channel_statusSending)).assertDoesNotExist()
        compose.runOnIdle { repo.attempts.single().ack.complete(true) }
        draft("Next draft")
        compose.onAllNodesWithText(TEXT).assertCountEquals(1)
        send().performClick()
        awaitAttempts(repo, 2)
        compose.runOnIdle { assertNotEquals(repo.attempts[0].id, repo.attempts[1].id) }
    }

    @Test fun clubRpcSuccessStopsSendingBeforeListenerEcho() {
        val repo = start()
        send().performClick()
        awaitAttempts(repo, 1)
        compose.runOnIdle { repo.attempts.single().ack.complete(true) }
        draft("")
        compose.onNodeWithText(str(R.string.channel_statusSending)).assertDoesNotExist()
        retry().assertDoesNotExist()
        compose.onNodeWithText(TEXT).assertIsDisplayed()
        compose.runOnIdle { repo.echo(0) }
        compose.onAllNodesWithText(TEXT).assertCountEquals(1)
    }

    @Test fun clubMessageIdMatchesDriverDoubleHashContract() {
        assertEquals("072829468747d3b0f656a700d9b3499d02a2b27bcf2dc243b11b24d5d1c7f876",
            clubPendingMessageId("club-draft-self", "00000000-0000-0000-0000-000000000001"))
    }

    @Test fun clubDefiniteRejectionRestoresUneditedDraftAndReusesOperation() {
        val repo = start()
        repo.failure = ClubException("invalid-argument")
        send().performClick()
        awaitAttempts(repo, 1)
        draft("")
        compose.runOnIdle { repo.attempts.single().ack.complete(false) }
        draft(TEXT)
        send().performClick()
        awaitAttempts(repo, 2)
        draft("")
        compose.runOnIdle {
            assertEquals(repo.attempts[0].id, repo.attempts[1].id)
            assertEquals(listOf(TEXT, TEXT), repo.attempts.map { it.text })
            repo.attempts[1].ack.complete(true)
        }
        draft("")
    }

    @Test fun clubNewDraftSurvivesFailureAndBubbleRetryUsesOriginalOperation() {
        val repo = start()
        send().performClick()
        awaitAttempts(repo, 1)
        input().performTextInput("Keep this")
        compose.runOnIdle { repo.attempts.single().ack.complete(false) }
        draft("Keep this")
        retry().performClick()
        awaitAttempts(repo, 2)
        compose.runOnIdle {
            assertEquals(repo.attempts[0].id, repo.attempts[1].id)
            repo.attempts[1].ack.complete(true)
        }
        draft("Keep this")
    }

    @Test fun clubEditedThenEmptiedDraftIsNotRestored() {
        val repo = start()
        repo.failure = ClubException("permission-denied")
        send().performClick()
        awaitAttempts(repo, 1)
        input().performTextInput("Changed")
        input().performTextClearance()
        compose.runOnIdle { repo.attempts.single().ack.complete(false) }
        draft("")
        retry().assertIsDisplayed()
    }

    @Test fun clubQuickEmojiPreservesTypedDraftOnSuccessAndFailure() {
        val repo = start()
        compose.onNodeWithTag(chatQuickEmojiTestTag("😊")).performClick()
        awaitAttempts(repo, 1)
        draft(TEXT)
        compose.runOnIdle { repo.attempts.single().ack.complete(false) }
        draft(TEXT)
        retry().performClick()
        awaitAttempts(repo, 2)
        compose.runOnIdle {
            assertEquals("😊", repo.attempts[0].text)
            assertEquals(repo.attempts[0].id, repo.attempts[1].id)
            repo.attempts[1].ack.complete(true)
        }
        draft(TEXT)
    }

    @Test fun clubEchoBeforeLateFailureDoesNotRestoreOrDuplicate() {
        val repo = start()
        send().performClick()
        awaitAttempts(repo, 1)
        compose.runOnIdle { repo.echo(0) }
        compose.waitForIdle()
        compose.runOnIdle { repo.attempts.single().ack.complete(false) }
        draft("")
        retry().assertDoesNotExist()
        compose.onAllNodesWithText(TEXT).assertCountEquals(1)
    }

    @Test fun clubCancellationRetainsRecoverableBubbleAndNewDraft() {
        val repo = start()
        send().performClick()
        awaitAttempts(repo, 1)
        input().performTextInput("Keep this")
        compose.runOnIdle { repo.attempts.single().ack.completeExceptionally(CancellationException("cancel")) }
        draft("Keep this")
        retry().performClick()
        awaitAttempts(repo, 2)
        compose.runOnIdle { assertEquals(repo.attempts[0].id, repo.attempts[1].id) }
    }

    @Test fun clubRotationRestoresExplicitRetryWithoutAutoSend() {
        val restoration = StateRestorationTester(compose)
        val repo = Repository()
        val cooldown = ChatSendCooldown()
        restoration.setContent { KccTheme {
            ClubChatRoute("club-draft-self", repo, LiveProfileRepository.EMPTY, cooldown)
        } }
        input().performTextInput(TEXT)
        send().performClick()
        awaitAttempts(repo, 1)
        input().performTextInput("Next draft")
        restoration.emulateSavedInstanceStateRestore()
        draft("Next draft")
        retry().assertIsDisplayed().performClick()
        awaitAttempts(repo, 2)
        compose.runOnIdle { assertEquals(repo.attempts[0].id, repo.attempts[1].id) }
    }

    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun retry() = compose.onNodeWithText(str(R.string.channel_statusFailedRetry))

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
        var failure: Exception = ClubException("unavailable")
        private val member = ClubMembership("draft-club", ClubRole.Member, "2026-10-30T00:00:00Z")
        private val rows = MutableStateFlow<ClubChatState>(ClubChatState.Ready(member, emptyList()))
        override fun chat() = rows
        fun echo(index: Int) {
            val attempt = attempts[index]
            rows.value = ClubChatState.Ready(member, listOf(ClubMessage(
                clubPendingMessageId("club-draft-self", attempt.id), "club-draft-self", "Me", attempt.text, 0L,
            )))
        }
        override suspend fun send(clubId: String, text: String, operationId: String) {
            assertEquals(member.clubId, clubId)
            val attempt = Attempt(text, operationId)
            attempts += attempt
            if (!attempt.ack.await()) throw failure
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
