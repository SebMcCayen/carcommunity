package com.kungsbackacarcommunity.app.friends

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class FriendsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun normalText_compactRowsPreserveActionsAndConfirmation() = checkScreen(390, 1f)
    @Test fun enlargedText_allActionsRemainReachable() = checkScreen(320, 1.5f)

    private fun checkScreen(width: Int, scale: Float) {
        val friend = FriendSummary("local-friend", "Local Kim", null, null)
        val request = FriendRequestSummary("local-request", "local-sender", "local-self",
            FriendRequestDirection.Incoming, FriendUser("local-sender", "Local Lee", null), null)
        val busy = mutableStateOf(emptySet<String>())
        val calls = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                KccTheme(darkTheme = scale == 1f) {
                    Box(Modifier.requiredSize(width.dp, 640.dp)) {
                        FriendsScreen(FriendsStatus.Loaded(listOf(friend), listOf(request), emptyList()),
                            AddFriendState.Idle, null, busy.value,
                            onSend = { calls += "send:$it" }, onChooseCandidate = {}, onDismissAdd = {},
                            onAccept = { calls += "accept:$it" }, onDecline = { calls += "decline:$it" },
                            onCancel = {}, onRemove = { calls += "remove:$it" }, onClearActionError = {},
                            onMessageFriend = { calls += "message:${it.uid}" },
                            onViewProfile = { calls += "profile:${it.uid}" })
                    }
                }
            }
        }
        scrollTo("friends_incoming_local-request")
        val requestBounds = compose.onNodeWithTag("friends_incoming_local-request").fetchSemanticsNode().boundsInRoot
        assertTrue("Request density: $requestBounds", requestBounds.height <= if (scale == 1f) 80f else 152f)
        compose.onNodeWithText(str(R.string.friends_accept)).performClick()
        compose.onNodeWithText(str(R.string.friends_decline)).performClick()
        compose.runOnIdle { busy.value = setOf(FriendsCoordinator.respondBusyKey(request.requestId)) }
        compose.onNodeWithText(str(R.string.friends_accept)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.friends_decline)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("accept:local-request", "decline:local-request"), calls)
            busy.value = emptySet()
        }

        scrollTo("friends_friend_local-friend")
        val friendBounds = compose.onNodeWithTag("friends_friend_local-friend").fetchSemanticsNode().boundsInRoot
        assertTrue("Friend density: $friendBounds", friendBounds.height <= if (scale == 1f) 96f else 168f)
        compose.onNodeWithText("Local Kim").performClick()
        compose.onNodeWithText(str(R.string.friends_message)).performClick()
        if (scale == 1f) compose.onNodeWithContentDescription(str(R.string.friends_remove)).performClick()
        else compose.onNodeWithText(str(R.string.friends_remove)).performClick()
        compose.runOnIdle { assertTrue(calls.none { it.startsWith("remove:") }) }
        compose.onNodeWithText(str(R.string.friends_removeCancel)).performClick()
        if (scale == 1f) compose.onNodeWithContentDescription(str(R.string.friends_remove)).performClick()
        else compose.onNodeWithText(str(R.string.friends_remove)).performClick()
        compose.onNodeWithText(str(R.string.friends_removeConfirmAction)).performClick()
        compose.runOnIdle {
            assertTrue("Profile callback", "profile:local-friend" in calls)
            assertTrue("DM callback", "message:local-friend" in calls)
            assertEquals(1, calls.count { it == "remove:local-friend" })
        }

        scrollTo("friends_search")
        compose.onNodeWithTag("friends_search").performTextInput("no local match")
        compose.onNodeWithTag("friends_friend_local-friend").assertDoesNotExist()
        compose.onNodeWithTag("friends_incoming_local-request").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.userSearch_noMatches)).assertIsDisplayed()
        compose.onNodeWithTag("friends_search").performTextClearance()
        scrollTo("friends_friend_local-friend")
        compose.onNodeWithText("Local Kim").assertIsDisplayed()

        // Add-by-nickname is still reachable from its compact disclosure.
        scrollTo("friends_add")
        compose.onNodeWithTag("friends_add").performClick()
        scrollTo("friends_add_nickname")
        compose.onNodeWithTag("friends_add_nickname").performTextInput("local-nickname")
        compose.onNodeWithTag("friends_list").performScrollToNode(
            androidx.compose.ui.test.hasText(str(R.string.friends_addAction)))
        compose.onNodeWithText(str(R.string.friends_addAction)).performClick()
        compose.runOnIdle { assertEquals(1, calls.count { it == "send:local-nickname" }) }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("friends_list").performScrollToNode(hasTestTag(tag))
    }
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
