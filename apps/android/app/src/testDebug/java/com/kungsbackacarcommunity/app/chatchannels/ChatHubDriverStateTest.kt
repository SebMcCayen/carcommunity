package com.kungsbackacarcommunity.app.chatchannels

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.driverfeed.DriverCursor
import com.kungsbackacarcommunity.app.driverfeed.DriverDetailPage
import com.kungsbackacarcommunity.app.driverfeed.DriverFeedRepository
import com.kungsbackacarcommunity.app.driverfeed.DriverFeedRoute
import com.kungsbackacarcommunity.app.driverfeed.DriverFeedStrings
import com.kungsbackacarcommunity.app.driverfeed.DriverPage
import com.kungsbackacarcommunity.app.driverfeed.DriverPost
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the production host's conditional composition, rather than only the coordinator. */
@RunWith(AndroidJUnit4::class)
class ChatHubDriverStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tabSwitchRetainsDetailButAccountChangeAndUnmountClearIt() {
        val uid = mutableStateOf("account-a")
        val mounted = mutableStateOf(true)
        val repository = LocalRepository()
        compose.setContent {
            KccTheme {
                if (mounted.value) ChatHubRoute(
                    uid = uid.value, communityChatRepository = null, convoyChatRepository = null,
                    friendsRepository = null, dmRepository = null, notificationsRepository = null,
                    notificationsCoordinator = null, communityUnread = false, convoysUnread = false,
                    friendsUnread = false, notificationsUnread = false, onClose = {},
                    driverContent = { DriverFeedRoute(uid.value, DriverFeedStrings { it }, repository = repository) },
                )
            }
        }
        driverTab()
        awaitText("Post for account-a")
        compose.onNodeWithText("Post for account-a").performClick()
        awaitText("Detail for account-a")
        compose.onNodeWithText(compose.activity.getString(R.string.chatHub_tabChats)).performClick()
        driverTab()
        awaitText("Detail for account-a")

        compose.runOnIdle { uid.value = "account-b" }
        compose.onNodeWithText("Detail for account-a").assertDoesNotExist()
        driverTab()
        awaitText("Post for account-b")
        compose.onNodeWithText("Detail for account-a").assertDoesNotExist()

        compose.runOnIdle { mounted.value = false }
        compose.runOnIdle { uid.value = "account-a"; mounted.value = true }
        driverTab()
        awaitText("Post for account-a")
        compose.onNodeWithText("Detail for account-a").assertDoesNotExist()
    }

    private fun driverTab() {
        compose.onNodeWithText(compose.activity.getString(R.string.chatHub_tabDriver)).performClick()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private class LocalRepository : DriverFeedRepository {
        private fun post(uid: String, text: String) = DriverPost("$uid-post", uid, uid, "", text, null, emptyList(), 0, 0)
        override suspend fun feed(uid: String, after: DriverCursor?) =
            DriverPage(listOf(post(uid, "Post for $uid")), null)
        override suspend fun detail(uid: String, id: String, after: DriverCursor?) =
            DriverDetailPage(post(uid, "Detail for $uid"), DriverPage(emptyList(), null))
        override fun invalidations(uid: String, threadId: String?) = flow {
            emit(Unit)
            awaitCancellation()
        }
    }
}
