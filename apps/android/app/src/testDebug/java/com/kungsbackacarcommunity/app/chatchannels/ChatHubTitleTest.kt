package com.kungsbackacarcommunity.app.chatchannels

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.dm.DmConversation
import com.kungsbackacarcommunity.app.dm.DmConversationsState
import com.kungsbackacarcommunity.app.dm.DmMessagePreview
import com.kungsbackacarcommunity.app.dm.DmOlderResult
import com.kungsbackacarcommunity.app.dm.DmRepository
import com.kungsbackacarcommunity.app.dm.DmSendError
import com.kungsbackacarcommunity.app.dm.DmSendResult
import com.kungsbackacarcommunity.app.dm.DmThreadState
import com.kungsbackacarcommunity.app.dm.DmUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The chat hub's top bar: gone at hub level, intact inside a conversation.
 *
 * Seb asked for the "Chat" heading in the hub's top-left to go — the four tabs
 * directly under it already name the section, so it was chrome eating vertical
 * space (the same reasoning as PR #627, which removed the "Notifications" and
 * "Messages" headings from two of these very tabs).
 *
 * What must NOT go with it is everything the bar carries in a sub-screen: the
 * Back arrow out of a thread/channel, and the conversation's own name. A convoy
 * channel in particular renders no title of its own, so the hub's bar is the only
 * thing telling a member which channel they are typing into.
 */
@RunWith(AndroidJUnit4::class)
class ChatHubTitleTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val conversation =
        DmConversation(
            conversationId = "u1__b",
            otherUser = DmUser(uid = "b", displayName = "Kim", avatarPath = null),
            lastMessage =
                DmMessagePreview(text = "See you there", senderUid = "b", createdAtMillis = 0L),
            unreadCount = 0,
            lastMessageAtMillis = 0L,
        )

    /** One conversation, an empty thread; every write is a no-op. */
    private class FakeDmRepository(private val conversation: DmConversation) : DmRepository {
        override fun observeConversations(uid: String): Flow<DmConversationsState> =
            flowOf(DmConversationsState.Loaded(listOf(conversation)))

        override fun observeThread(conversationId: String): Flow<DmThreadState> =
            flowOf(DmThreadState.Loaded(emptyList()))

        override suspend fun sendMessage(
            toUid: String,
            text: String,
            clientId: String?,
        ): DmSendResult = DmSendResult.Failed(DmSendError.Generic)

        override suspend fun loadOlder(conversationId: String, before: String): DmOlderResult =
            DmOlderResult.Failed

        override suspend fun markRead(conversationId: String) = Unit
    }

    private fun showHub(dmRepository: DmRepository? = null) {
        composeTestRule.setContent {
            KccTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    ChatHubPopup(
                        uid = "u1",
                        communityChatRepository = null,
                        convoyChatRepository = null,
                        friendsRepository = null,
                        dmRepository = dmRepository,
                        notificationsRepository = null,
                        notificationsCoordinator = null,
                        communityUnread = false,
                        convoysUnread = false,
                        friendsUnread = false,
                        notificationsUnread = false,
                        onClose = {},
                    )
                }
            }
        }
    }

    /**
     * The change itself: at hub level the heading is gone. The tab row is asserted
     * alongside it so this cannot pass by the hub failing to render at all.
     */
    @Test
    fun hubLevel_hasNoTitleHeading() {
        showHub()

        composeTestRule.onNodeWithText(str(R.string.chatHub_title)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.chatHub_tabCommunity)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.chatHub_tabFriends)).assertIsDisplayed()
    }

    /**
     * Nothing was lost with the bar: at hub level it never held a Back arrow (that
     * was always conditional on being in a sub-screen) and there is no close (X) —
     * the hub is dismissed by the panel's drag handle, the map strip above it, or
     * system Back.
     */
    @Test
    fun hubLevel_hadNoBackArrowToLose() {
        showHub()

        composeTestRule.onNodeWithContentDescription(str(R.string.chatHub_back)).assertDoesNotExist()
    }

    /**
     * Accessibility: a removed heading must not leave the panel anonymous. The hub
     * opens over the map, and the shared shell panel labels only its drag handle,
     * so without a pane title a TalkBack user would get no announcement of what
     * just appeared. The pane title carries the name the heading used to — same
     * string, no pixels.
     */
    @Test
    fun hubLevel_stillNamesItselfToAScreenReader() {
        showHub()

        composeTestRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    str(R.string.chatHub_title),
                ),
            )
            .assertExists()
    }

    /**
     * The half that must survive: opening a conversation brings the bar back with
     * the other member's name and a working Back arrow, and Back returns to the
     * tabs. A convoy channel takes the identical path through the same `when`, and
     * unlike a DM thread it has no title of its own to fall back on.
     */
    @Test
    fun openingAThread_bringsBackTheBarWithTheNameAndAWorkingBack() {
        showHub(dmRepository = FakeDmRepository(conversation))

        composeTestRule.onNodeWithText(str(R.string.chatHub_tabFriends)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText("Kim").onFirst().performClick()
        composeTestRule.waitForIdle()

        // Two nodes read "Kim" in a DM thread: the hub's bar and the thread's own
        // AeroPage title (pre-existing — a convoy channel has only the bar). The
        // count is asserted rather than glossed so this test notices if either
        // disappears.
        composeTestRule.onAllNodesWithText("Kim").assertCountEquals(2)
        composeTestRule.onNodeWithContentDescription(str(R.string.chatHub_back)).assertIsDisplayed()
        // The tab row is hidden inside a sub-screen, so the bar is the only way out.
        composeTestRule.onNodeWithText(str(R.string.chatHub_tabFriends)).assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription(str(R.string.chatHub_back)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(str(R.string.chatHub_tabFriends)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.chatHub_back)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.chatHub_title)).assertDoesNotExist()
    }
}
