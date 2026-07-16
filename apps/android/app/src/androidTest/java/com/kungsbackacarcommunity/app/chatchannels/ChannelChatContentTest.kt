package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the shared group-channel body's sender headers — the
 * "tap a member → open their profile" entry point on the community and convoy
 * channels.
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
                )
            }
        }
    }

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
}
