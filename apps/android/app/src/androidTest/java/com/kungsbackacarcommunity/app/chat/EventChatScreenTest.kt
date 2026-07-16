package com.kungsbackacarcommunity.app.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.moderation.MESSAGE_ACTIONS_BLOCK_TEST_TAG
import com.kungsbackacarcommunity.app.moderation.MESSAGE_ACTIONS_REPORT_TEST_TAG
import com.kungsbackacarcommunity.app.moderation.MESSAGE_ACTIONS_SHEET_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the event-chat screen (Phase 12 slice 10).
 *
 * Moderation now lives behind a LONG-PRESS on another member's message, which
 * opens the shared moderation sheet — the inline Report/Block text buttons are
 * gone, so these drive the gesture instead.
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

    private fun longPress(text: String) =
        composeTestRule.onNodeWithText(text).performTouchInput { longClick() }

    @Test
    fun longPressingOthersMessage_opensReasonPicker_andReports() {
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
        longPress("Body of m9")
        // Event chat is the one surface with a report callable, so the sheet's
        // report row is live here and opens the real reason picker.
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_REPORT_TEST_TAG).performClick()
        composeTestRule.onNodeWithText(str(R.string.chat_reportReasonSpam)).performClick()
        assertEquals("m9" to ChatReportReason.SPAM, reported)
    }

    @Test
    fun longPressingOthersMessage_blockThenConfirm_blocksAuthor() {
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
        longPress("Body of m9")
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_BLOCK_TEST_TAG).performClick()
        composeTestRule.onNodeWithText(str(R.string.blocking_blockConfirmAction)).performClick()
        assertEquals("other", blocked)
    }

    @Test
    fun sheetHasNoBlockRow_whenBlockingIsUnwired() {
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
                    // canBlock defaults to false: a config-less build has no
                    // blocking repository, so the row must be absent, not broken.
                )
            }
        }
        longPress("Body of m9")
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_REPORT_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_BLOCK_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun ownMessage_longPressOpensNoSheet() {
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
        longPress("Body of m1")
        // You can neither block nor report yourself.
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_SHEET_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun removedMessage_longPressOpensNoSheet() {
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
                    canBlock = true,
                    onBlock = {},
                )
            }
        }
        // A removed message shows a neutral placeholder and has no body to report.
        longPress(str(R.string.chat_removedMessage))
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_SHEET_TEST_TAG).assertDoesNotExist()
    }
}
