package com.kungsbackacarcommunity.app.chat

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
}
