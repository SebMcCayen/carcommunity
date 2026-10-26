package com.kungsbackacarcommunity.app.dm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.friends.FriendSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** S35: long friend lists remain lazy and reachable inside the shared reading host's scroll column. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class NewDialogueFriendListTest {
    @get:Rule val compose = createComposeRule()

    @Test fun normalText_lastFriendCanBeSelected() = checkLastFriend(1f)
    @Test fun enlargedText_lastFriendCanBeSelected() = checkLastFriend(1.5f)

    private fun checkLastFriend(scale: Float) {
        val friends = (0 until 40).map { FriendSummary("local-$it", "Local member $it", null, null) }
        var picked: FriendSummary? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                KccTheme {
                    Column(Modifier.requiredSize(320.dp, 640.dp).verticalScroll(rememberScrollState())) {
                        NewDialogueFriendList(friends) { picked = it }
                    }
                }
            }
        }
        // A full eager Column would compose the last row immediately and resolve every avatar.
        compose.onNodeWithTag(newDialogueFriendRowTestTag(friends.last().uid)).assertDoesNotExist()
        compose.onNodeWithTag(NEW_DIALOGUE_FRIENDS_TEST_TAG).performScrollToIndex(friends.lastIndex)
        val last = compose.onNodeWithTag(newDialogueFriendRowTestTag(friends.last().uid))
        last.assertIsDisplayed()
        val bounds = last.fetchSemanticsNode().boundsInRoot
        assertTrue("Friend hit target: $bounds", bounds.height >= 48f && bounds.width >= 48f)
        last.performClick()
        compose.runOnIdle { assertEquals(friends.last(), picked) }
    }
}
