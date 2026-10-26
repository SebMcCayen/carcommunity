package com.kungsbackacarcommunity.app.events

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.dm.DmConversationsState
import com.kungsbackacarcommunity.app.dm.DmOlderResult
import com.kungsbackacarcommunity.app.dm.DmRepository
import com.kungsbackacarcommunity.app.dm.DmSendResult
import com.kungsbackacarcommunity.app.dm.DmThreadState
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsData
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsResult
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventShareSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longFriendListCanScrollToAndShareWithLastFriend_onlyAfterExplicitTap() {
        val dm = LocalDm()
        var confirmations = 0
        var dismissals = 0
        val event = EventSummary(
            id = "test-event", title = "Test event", summary = null,
            startsAtMillis = 1_793_466_000_000L, endsAtMillis = null,
            approximateArea = null, locationName = "Actual test place",
            isOfficial = false, status = EventStatus.PUBLISHED, counts = RsvpCounts.EMPTY,
        )
        compose.setContent {
            KccTheme {
                EventShareSheet(
                    eventId = event.id, eventTitle = event.title, event = event,
                    friendsRepository = LocalFriends, dmRepository = dm,
                    onShared = { confirmations++ }, onSendFailed = {}, onDismiss = { dismissals++ },
                )
            }
        }
        compose.onNodeWithText("Actual test place").performScrollTo().assertIsDisplayed()
        assertTrue(dm.targets.isEmpty())
        compose.onNodeWithTag(shareEventFriendRowTestTag("friend-29")).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("friend-29"), dm.targets)
            assertEquals("test-event", EventShareLinks.findAll(dm.body).single().link.eventId)
            assertEquals(1, confirmations)
            assertEquals(1, dismissals)
        }
    }

    private object LocalFriends : FriendsRepository {
        override suspend fun list() = FriendsResult.Loaded(FriendsData(
            List(30) { FriendSummary("friend-$it", "Test friend ${it.toString().padStart(2, '0')}", null, null) },
            emptyList(), emptyList(),
        ))
        override suspend fun sendRequestByNickname(nickname: String): Nothing = error("Unexpected write")
        override suspend fun sendRequestToUid(toUid: String): Nothing = error("Unexpected write")
        override suspend fun respond(requestId: String, accept: Boolean): Nothing = error("Unexpected write")
        override suspend fun cancelRequest(toUid: String): Nothing = error("Unexpected write")
        override suspend fun remove(friendUid: String): Nothing = error("Unexpected write")
    }

    private class LocalDm : DmRepository {
        val targets = mutableListOf<String>()
        var body = ""
        override fun observeConversations(uid: String) = emptyFlow<DmConversationsState>()
        override fun observeThread(conversationId: String) = emptyFlow<DmThreadState>()
        override suspend fun sendMessage(toUid: String, text: String, clientId: String?): DmSendResult {
            targets += toUid
            body = text
            return DmSendResult.Sent("test-conversation", "test-message")
        }
        override suspend fun loadOlder(conversationId: String, before: String) = DmOlderResult.Failed
        override suspend fun markRead(conversationId: String) = Unit
    }
}
