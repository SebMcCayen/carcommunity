package com.kungsbackacarcommunity.app.chat

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the event-chat screen (Phase 12 slice 10).
 */
@RunWith(AndroidJUnit4::class)
class EventChatScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun message(id: String, author: String, uid: String, removed: Boolean = false) =
        ChatMessage(
            id = id,
            authorUserId = uid,
            authorDisplayName = author,
            message = if (removed) "" else "Body of $id",
            isRemoved = removed,
            createdAtMillis = 0L,
        )

    @Test
    fun nonParticipant_seesGate() {
        composeTestRule.setContent {
            KccTheme {
                EventChatScreen(
                    state = ChatMessagesState.Loaded(emptyList()),
                    currentUid = "me",
                    canParticipate = false,
                    sendStatus = ChatSendStatus.Idle,
                    reportStatus = ChatReportStatus.Idle,
                    onSend = {},
                    onReport = { _, _ -> },
                    onReportDismiss = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.chat_memberRequired)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.chat_sendButton)).assertDoesNotExist()
    }

    @Test
    fun participant_removedMessage_showsPlaceholder_and_sendDisabledWhenEmpty() {
        composeTestRule.setContent {
            KccTheme {
                EventChatScreen(
                    state = ChatMessagesState.Loaded(listOf(message("m1", "Ada", "other", removed = true))),
                    currentUid = "me",
                    canParticipate = true,
                    sendStatus = ChatSendStatus.Idle,
                    reportStatus = ChatReportStatus.Idle,
                    onSend = {},
                    onReport = { _, _ -> },
                    onReportDismiss = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.chat_removedMessage)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.chat_sendButton)).assertIsNotEnabled()
    }

    @Test
    fun participant_typingThenSend_invokesCallback() {
        var sent: String? = null
        composeTestRule.setContent {
            KccTheme {
                EventChatScreen(
                    state = ChatMessagesState.Loaded(emptyList()),
                    currentUid = "me",
                    canParticipate = true,
                    sendStatus = ChatSendStatus.Idle,
                    reportStatus = ChatReportStatus.Idle,
                    onSend = { sent = it },
                    onReport = { _, _ -> },
                    onReportDismiss = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.chat_inputPlaceholder)).performTextInput("Nice meet")
        composeTestRule.onNodeWithText(str(R.string.chat_sendButton)).performClick()
        assertEquals("Nice meet", sent)
    }

    @Test
    fun reportingOthersMessage_opensReasonPicker_andReports() {
        var reported: Pair<String, ChatReportReason>? = null
        composeTestRule.setContent {
            KccTheme {
                EventChatScreen(
                    state = ChatMessagesState.Loaded(listOf(message("m9", "Ada", "other"))),
                    currentUid = "me",
                    canParticipate = true,
                    sendStatus = ChatSendStatus.Idle,
                    reportStatus = ChatReportStatus.Idle,
                    onSend = {},
                    onReport = { id, reason -> reported = id to reason },
                    onReportDismiss = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.chat_reportMessage)).performClick()
        composeTestRule.onNodeWithText(str(R.string.chat_reportReasonSpam)).performClick()
        assertEquals("m9" to ChatReportReason.SPAM, reported)
    }

    @Test
    fun blockingOthersMessage_confirmDialog_blocksAuthor() {
        var blocked: String? = null
        composeTestRule.setContent {
            KccTheme {
                EventChatScreen(
                    state = ChatMessagesState.Loaded(listOf(message("m9", "Ada", "other"))),
                    currentUid = "me",
                    canParticipate = true,
                    sendStatus = ChatSendStatus.Idle,
                    reportStatus = ChatReportStatus.Idle,
                    onSend = {},
                    onReport = { _, _ -> },
                    onReportDismiss = {},
                    onBack = {},
                    canBlock = true,
                    onBlock = { blocked = it },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.blocking_blockUser)).performClick()
        composeTestRule.onNodeWithText(str(R.string.blocking_blockConfirmTitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.blocking_blockConfirmAction)).performClick()
        assertEquals("other", blocked)
    }

    /**
     * Renders the screen with the profile entry point wired. Unlike the block /
     * report cases above, these tests only vary the roster, so they share a setup.
     */
    private fun setContentWithProfile(
        messages: List<ChatMessage>,
        onViewProfile: (String) -> Unit,
    ) {
        composeTestRule.setContent {
            KccTheme {
                EventChatScreen(
                    state = ChatMessagesState.Loaded(messages),
                    currentUid = "me",
                    canParticipate = true,
                    sendStatus = ChatSendStatus.Idle,
                    reportStatus = ChatReportStatus.Idle,
                    onSend = {},
                    onReport = { _, _ -> },
                    onReportDismiss = {},
                    onBack = {},
                    onViewProfile = onViewProfile,
                )
            }
        }
    }

    @Test
    fun tappingAuthorName_opensThatAuthorsProfile() {
        var opened: String? = null
        setContentWithProfile(listOf(message("m1", "Ada", "other"))) { opened = it }

        composeTestRule.onNodeWithText("Ada").performClick()

        assertEquals("other", opened)
    }

    @Test
    fun ownMessage_exposesNoProfileAffordance() {
        var opened: String? = null
        // Your own message still renders your name (unlike the group channels,
        // where own messages are a bare bubble), so the name is present but must
        // never be a button — you don't open your own profile from your own message.
        setContentWithProfile(listOf(message("m1", "Me", "me"))) { opened = it }

        composeTestRule.onNodeWithText("Me").assertHasNoClickAction()

        assertNull(opened)
    }

    @Test
    fun blankAuthorUid_exposesNoProfileAffordance() {
        var opened: String? = null
        // A malformed message would otherwise open a dead profile route. Assert the
        // affordance is absent rather than clicking it and checking nothing
        // happened: a click on a node with no click action silently does nothing,
        // so that would also "pass" against an author wired to a dead no-op button —
        // which screen readers would still announce as a tappable button.
        setContentWithProfile(listOf(message("m1", "Ghost", ""))) { opened = it }

        composeTestRule.onNodeWithText("Ghost").assertHasNoClickAction()

        assertNull(opened)
    }

    @Test
    fun ownMessage_showsNoBlockAction() {
        composeTestRule.setContent {
            KccTheme {
                EventChatScreen(
                    state = ChatMessagesState.Loaded(listOf(message("m1", "Me", "me"))),
                    currentUid = "me",
                    canParticipate = true,
                    sendStatus = ChatSendStatus.Idle,
                    reportStatus = ChatReportStatus.Idle,
                    onSend = {},
                    onReport = { _, _ -> },
                    onReportDismiss = {},
                    onBack = {},
                    canBlock = true,
                    onBlock = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.blocking_blockUser)).assertDoesNotExist()
    }
}
