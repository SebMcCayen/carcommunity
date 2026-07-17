package com.kungsbackacarcommunity.app.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.ShellBottomBar
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the bottom bar's dual-purpose centre control: "+" when
 * idle, STOP while a live session runs.
 *
 * Tested against [ShellBottomBar] directly rather than the whole shell, because
 * the sharing state needs a live-location repository the shell test has no way
 * to provide (it renders the no-Firebase configuration).
 */
@RunWith(AndroidJUnit4::class)
class ShellBottomBarStopTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun setBar(
        isSharing: Boolean,
        onSelect: (ShellTab) -> Unit = {},
        onStop: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KccTheme {
                ShellBottomBar(
                    selected = ShellTab.Map,
                    onSelect = onSelect,
                    isSharing = isSharing,
                    onStopLiveShare = onStop,
                )
            }
        }
    }

    @Test
    fun notSharing_centreControlIsTheCreatePlus() {
        setBar(isSharing = false)

        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabCreate)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.liveLocation_stop)).assertDoesNotExist()
    }

    @Test
    fun sharing_centreControlBecomesTheStopSign() {
        setBar(isSharing = true)

        // The whole point of the change: while a session runs there is no "+".
        composeTestRule.onNodeWithContentDescription(str(R.string.liveLocation_stop)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabCreate)).assertDoesNotExist()
    }

    @Test
    fun notSharing_tappingTheCentreControlRaisesCreate() {
        var selected: ShellTab? = null
        var stopped = false
        setBar(isSharing = false, onSelect = { selected = it }, onStop = { stopped = true })

        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabCreate)).performClick()

        assertEquals(ShellTab.Create, selected)
        assertEquals(false, stopped)
    }

    @Test
    fun sharing_tappingTheStopSignStopsTheSessionAndNeverOpensCreate() {
        var selected: ShellTab? = null
        var stopped = 0
        setBar(isSharing = true, onSelect = { selected = it }, onStop = { stopped += 1 })

        composeTestRule.onNodeWithContentDescription(str(R.string.liveLocation_stop)).performClick()

        assertEquals(1, stopped)
        assertNull("Stopping must not raise the create chooser", selected)
    }
}
