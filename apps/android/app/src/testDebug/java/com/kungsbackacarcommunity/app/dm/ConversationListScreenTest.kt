package com.kungsbackacarcommunity.app.dm

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The DM inbox's title header, which the chat hub's Friends tab suppresses
 * because the tab already names the section. Everything that DOES something —
 * the rows and their taps — must survive that.
 */
@RunWith(AndroidJUnit4::class)
class ConversationListScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val conversation =
        DmConversation(
            conversationId = "a__b",
            otherUser = DmUser(uid = "b", displayName = "Kim", avatarPath = null),
            lastMessage =
                DmMessagePreview(text = "See you there", senderUid = "b", createdAtMillis = 0L),
            unreadCount = 0,
            lastMessageAtMillis = 0L,
        )

    @Test
    fun standaloneInbox_keepsItsTitle() {
        composeTestRule.setContent {
            KccTheme {
                ConversationListScreen(
                    state = DmConversationsState.Loaded(listOf(conversation)),
                    onOpenConversation = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.dm_title)).assertIsDisplayed()
    }

    @Test
    fun insideTheChatHub_theTitleIsGoneButTheRowsAreNot() {
        var opened: DmConversation? = null
        composeTestRule.setContent {
            KccTheme {
                ConversationListScreen(
                    state = DmConversationsState.Loaded(listOf(conversation)),
                    onOpenConversation = { opened = it },
                    showTitle = false,
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.dm_title)).assertDoesNotExist()
        composeTestRule.onNodeWithText("Kim").performScrollTo().performClick()
        assertEquals(conversation, opened)
    }

    @Test
    fun withoutTheTitle_theEmptyStateStillExplainsItself() {
        // Removing the header must not leave a blank page: the empty-state copy
        // is the only thing left to read.
        composeTestRule.setContent {
            KccTheme {
                ConversationListScreen(
                    state = DmConversationsState.Loaded(emptyList()),
                    onOpenConversation = {},
                    showTitle = false,
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.dm_empty)).assertIsDisplayed()
    }
}
