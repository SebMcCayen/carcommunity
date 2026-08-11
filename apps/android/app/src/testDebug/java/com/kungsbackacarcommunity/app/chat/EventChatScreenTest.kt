package com.kungsbackacarcommunity.app.chat

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import org.junit.Assert.assertNull
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

    private fun message(
        id: String,
        author: String,
        uid: String,
        removed: Boolean = false,
        autoHidden: Boolean = false,
    ) =
        ChatMessage(
            id = id,
            authorUserId = uid,
            authorDisplayName = author,
            // Auto-hide PRESERVES the body (reveal is client-local); only removal blanks it.
            message = if (removed) "" else "Body of $id",
            isRemoved = removed,
            isAutoHidden = autoHidden,
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
        // Event chat has a real report callable, so its row survives even here,
        // where the block row is omitted — which is also what keeps the sheet
        // worth opening at all on this surface.
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_REPORT_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_REPORT_TEST_TAG).assertIsEnabled()
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
    fun autoHiddenMessage_showsRevealControl_andTappingRevealsBody() {
        composeTestRule.setContent {
            KccTheme {
                EventChatScreen(
                    state = ChatMessagesState.Loaded(listOf(message("m1", "Ada", "other", autoHidden = true))),
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
        // Collapsed: placeholder + reveal control shown, the body hidden.
        composeTestRule.onNodeWithText(str(R.string.chat_reportedHidden)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.chat_showReportedMessage)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Body of m1").assertDoesNotExist()

        // Tapping the reveal expands the original content locally.
        composeTestRule.onNodeWithText(str(R.string.chat_showReportedMessage)).performClick()
        composeTestRule.onNodeWithText("Body of m1").assertIsDisplayed()
    }

    @Test
    fun removedMessage_showsPlaceholder_andOffersNoRevealControl() {
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
        // A removed message is a permanent tombstone: placeholder, and NEVER a
        // reveal control (its body is gone server-side).
        composeTestRule.onNodeWithText(str(R.string.chat_removedMessage)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.chat_showReportedMessage)).assertDoesNotExist()
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
