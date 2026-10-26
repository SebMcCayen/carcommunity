package com.kungsbackacarcommunity.app.blocking

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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
@Config(qualifiers = "w320dp-h640dp")
class BlockingScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun missingName_usesLocalizedFallback_withoutExposingInternalId() {
        compose.setContent {
            KccTheme {
                BlockingScreen(
                    BlockedUsersState.Loaded(listOf(BlockedUser("private-user-id", " ", null))),
                    BlockActionStatus.Idle, {}, {},
                )
            }
        }
        compose.onNodeWithText(str(R.string.blocking_aboutTitle)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.blocking_unknownUser)).assertIsDisplayed()
        compose.onNodeWithText("private-user-id").assertDoesNotExist()
    }

    @Test
    fun lastUser_isReachable_andUnblockRequiresConfirmation() {
        val users = (1..20).map { BlockedUser("fixture-$it", "Test user $it", null) }
        var unblocked: String? = null
        compose.setContent {
            KccTheme {
                BlockingScreen(BlockedUsersState.Loaded(users), BlockActionStatus.Idle,
                    { unblocked = it }, {})
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Test user 20"))
        compose.onAllNodesWithText(str(R.string.blocking_unblock)).onLast().performClick()
        assertEquals(null, unblocked)
        compose.onNodeWithText(str(R.string.blocking_unblockConfirmTitle)).assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.blocking_unblockConfirmAction)).onLast().performClick()
        assertEquals("fixture-20", unblocked)
    }

    @Test
    fun working_disablesUnblock() {
        compose.setContent {
            KccTheme {
                BlockingScreen(
                    BlockedUsersState.Loaded(listOf(BlockedUser("fixture", "Test user", null))),
                    BlockActionStatus.Working, {}, {},
                )
            }
        }
        compose.onNodeWithText(str(R.string.blocking_unblock)).assertIsNotEnabled()
    }

    @Test
    fun largeText_nameAndActionRemainReachable_withoutClippingIntoEachOther() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                KccTheme {
                    BlockingScreen(
                        BlockedUsersState.Loaded(listOf(BlockedUser("fixture", "Long fixture display name", null))),
                        BlockActionStatus.Idle, {}, {},
                    )
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Long fixture display name"))
        compose.onNodeWithText("Long fixture display name").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.blocking_unblock)).performScrollTo().assertIsDisplayed()
        val nameBounds = compose.onNodeWithText("Long fixture display name").fetchSemanticsNode().boundsInRoot
        val actionBounds = compose.onNodeWithText(str(R.string.blocking_unblock)).fetchSemanticsNode().boundsInRoot
        assertTrue("The action must wrap below the name at large text", actionBounds.top >= nameBounds.bottom)
    }
}
