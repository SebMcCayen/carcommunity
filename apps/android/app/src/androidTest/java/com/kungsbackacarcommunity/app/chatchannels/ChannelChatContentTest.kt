package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
    ) {
        composeTestRule.setContent {
            KccTheme {
                ChannelChatContent(
                    messages = messages,
                    currentUid = "me",
                    loading = false,
                    emptyText = "empty",
                    sendStatus = ChannelSendStatus.Idle,
                    canLoadOlder = false,
                    isLoadingOlder = false,
                    onSend = {},
                    onLoadOlder = {},
                    onResetError = {},
                    onViewProfile = onViewProfile,
                    onBlock = onBlock,
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
    fun blankSenderUid_doesNotNavigate() {
        var opened: String? = null
        // A malformed message would otherwise open a dead profile route.
        setContent(
            messages = listOf(message("m1", senderUid = "", name = "Ghost")),
            onViewProfile = { opened = it },
        )

        composeTestRule.onNodeWithText("Ghost").performClick()

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
    fun channelReportIsDisabled_becauseNoReportBackendExists() {
        // The community/convoy channels have no report callable. The row must be
        // visibly present but NOT actionable — a report the client cannot file
        // must never look like one it filed.
        setContent(
            messages = listOf(message("m1", senderUid = "other", name = "Alice")),
            onViewProfile = null,
            onBlock = {},
        )

        longPress("Body of m1")

        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_REPORT_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MESSAGE_ACTIONS_REPORT_TEST_TAG).assertIsNotEnabled()
    }

    @Test
    fun sheetHasNoBlockRow_whenBlockingIsUnwired() {
        // Config-less build: the row is absent rather than present-and-broken.
        setContent(
            messages = listOf(message("m1", senderUid = "other", name = "Alice")),
            onViewProfile = null,
            onBlock = null,
        )

        longPress("Body of m1")

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
