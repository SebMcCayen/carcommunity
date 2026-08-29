package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import com.kungsbackacarcommunity.app.moderation.MESSAGE_ACTIONS_BLOCK_TEST_TAG
import com.kungsbackacarcommunity.app.moderation.MESSAGE_ACTIONS_REPORT_TEST_TAG
import com.kungsbackacarcommunity.app.moderation.MESSAGE_ACTIONS_SHEET_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the shared group-channel body: the sender headers ("tap a
 * member → open their profile") and the long-press moderation sheet, on the
 * community and convoy channels.
 */
@RunWith(AndroidJUnit4::class)
class ChannelChatContentTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun message(id: String, senderUid: String, name: String?) =
        ChannelMessage(
            id = id,
            senderUid = senderUid,
            text = "Body of $id",
            senderDisplayName = name,
            senderAvatarPath = null,
            createdAtMillis = 0L,
            createdAtIso = null,
        )

    private fun setContent(
        messages: List<ChannelMessage>,
        onViewProfile: ((String) -> Unit)?,
        onBlock: ((String) -> Unit)? = null,
        surface: ChatSurface = ChatSurface.CommunityChannel,
        onReport: ((String, com.kungsbackacarcommunity.app.chat.ChatReportReason) -> Unit)? = null,
    ) {
        composeTestRule.setContent {
            KccTheme {
                ChannelChatContent(
                    messages = messages,
                    currentUid = "me",
                    loading = false,
                    emptyText = "empty",
                    canLoadOlder = false,
                    isLoadingOlder = false,
                    onSend = { _, _, _ -> },
                    onRetry = {},
                    onLoadOlder = {},
                    surface = surface,
                    onViewProfile = onViewProfile,
                    onBlock = onBlock,
                    onReport = onReport,
                )
            }
        }
    }

    private fun longPress(text: String) =
        composeTestRule.onNodeWithText(text).performTouchInput { longClick() }

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun tappingSenderName_opensThatMembersProfile() {
        var opened: String? = null
        setContent(
            messages = listOf(message("m1", senderUid = "other", name = "Alice")),
            onViewProfile = { opened = it },
        )

        composeTestRule.onNodeWithText("Alice").performClick()

        assertEquals("other", opened)
    }

    @Test
    fun ownMessage_hasNoSenderAffordance() {
        var opened: String? = null
        // The caller's own messages render as a bare bubble with no sender header,
        // so there is nothing to tap — your own message never opens your profile.
        setContent(
            messages = listOf(message("m1", senderUid = "me", name = "Me")),
            onViewProfile = { opened = it },
        )

        composeTestRule.onNodeWithText("Me").assertDoesNotExist()
        assertNull(opened)
    }

    @Test
    fun blankSenderUid_exposesNoProfileAffordance() {
        var opened: String? = null
        // A malformed message would otherwise open a dead profile route. Assert the
        // affordance is absent rather than clicking it and checking nothing
        // happened: a click on a node with no click action silently does nothing,
        // so it would also "pass" against a sender wired to a dead no-op button —
        // which screen readers would still announce as a tappable button.
        setContent(
            messages = listOf(message("m1", senderUid = "", name = "Ghost")),
            onViewProfile = { opened = it },
        )

        composeTestRule.onNodeWithText("Ghost").assertHasNoClickAction()

        assertNull(opened)
    }

    @Test
    fun longPressingOthersBubble_blockThenConfirm_blocksSender() {
        var blocked: String? = null
        setContent(
            messages = listOf(message("m1", senderUid = "other", name = "Alice")),
            onViewProfile = null,
            onBlock = { blocked = it },
        )

        longPress("Body of m1")
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_BLOCK_TEST_TAG).performClick()
        // The confirm dialog stands between the sheet and the block.
        assertNull("block must not fire before confirmation", blocked)
        composeTestRule.onNodeWithText(str(R.string.blocking_blockConfirmAction)).performClick()

        assertEquals("other", blocked)
    }

    @Test
    fun communityChannel_withoutAWiredReportLambda_hasNoReportRow() {
        // The community report callable exists, but this route passed NO submit
        // lambda, so the row must stay ABSENT rather than open a picker that can't
        // submit. The sheet still opens (block is available), so this can't pass by
        // the sheet failing to show.
        setContent(
            messages = listOf(message("m1", senderUid = "other", name = "Alice")),
            onViewProfile = null,
            onBlock = {},
            surface = ChatSurface.CommunityChannel,
            onReport = null,
        )

        longPress("Body of m1")

        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_SHEET_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_BLOCK_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_REPORT_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun communityChannel_reportRow_opensReasonPicker_andSubmitsChosenReason() {
        // The wired path: a submit lambda is present, so the report row shows on
        // another member's message. Tapping it opens the shared reason picker, and
        // choosing a reason fires the report with that message's id + the reason.
        var reported: Pair<String, com.kungsbackacarcommunity.app.chat.ChatReportReason>? = null
        setContent(
            messages = listOf(message("m1", senderUid = "other", name = "Alice")),
            onViewProfile = null,
            onBlock = {},
            surface = ChatSurface.CommunityChannel,
            onReport = { id, reason -> reported = id to reason },
        )

        longPress("Body of m1")
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_REPORT_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_REPORT_TEST_TAG).performClick()

        // The reason picker opens; picking "Spam" submits.
        composeTestRule.onNodeWithText(str(R.string.chat_reportReasonSpam)).performClick()

        assertEquals(
            "m1" to com.kungsbackacarcommunity.app.chat.ChatReportReason.SPAM,
            reported,
        )
    }

    @Test
    fun communityChannel_reportRow_isAbsentOnOwnMessage() {
        // You can't report yourself even with the callable wired.
        setContent(
            messages = listOf(message("m1", senderUid = "me", name = "Me")),
            onViewProfile = null,
            onBlock = {},
            surface = ChatSurface.CommunityChannel,
            onReport = { _, _ -> },
        )

        longPress("Body of m1")

        // Own-message long-press opens no sheet at all (nothing to block or report).
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_SHEET_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun convoyChannel_hasNoReportRow_becauseNoReportBackendExists() {
        // Same gap, second surface: ConvoyChannel must not inherit the community
        // channel's wiring by accident now that `surface` has no default.
        setContent(
            messages = listOf(message("m1", senderUid = "other", name = "Alice")),
            onViewProfile = null,
            onBlock = {},
            surface = ChatSurface.ConvoyChannel,
        )

        longPress("Body of m1")

        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_SHEET_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_REPORT_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun longPressOpensNothing_whenNeitherBlockNorReportIsAvailable() {
        // Config-less build on a channel: block unwired AND report backend-less,
        // so every row would be omitted. Rather than opening a sheet containing
        // nothing but its own Close button, the long-press does nothing at all.
        setContent(
            messages = listOf(message("m1", senderUid = "other", name = "Alice")),
            onViewProfile = null,
            onBlock = null,
        )

        longPress("Body of m1")

        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_SHEET_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_BLOCK_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun ownBubble_longPressOpensNoSheet() {
        setContent(
            messages = listOf(message("m1", senderUid = "me", name = "Me")),
            onViewProfile = null,
            onBlock = {},
        )

        longPress("Body of m1")

        // You can neither block nor report yourself.
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_SHEET_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun blankSenderUid_longPressOpensNoSheet() {
        // A malformed message would otherwise open a sheet targeting nobody.
        setContent(
            messages = listOf(message("m1", senderUid = "", name = "Ghost")),
            onViewProfile = null,
            onBlock = {},
        )

        longPress("Body of m1")

        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_SHEET_TEST_TAG).assertDoesNotExist()
    }
}
