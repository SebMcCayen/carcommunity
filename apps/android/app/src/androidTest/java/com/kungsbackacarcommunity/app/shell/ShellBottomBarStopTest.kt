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
 * idle, and — while a live session runs — the live-session disc that raises the
 * stop sheet (whose only action is ending the session).
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
        onManage: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KccTheme {
                ShellBottomBar(
                    selected = ShellTab.Map,
                    onSelect = onSelect,
                    isSharing = isSharing,
                    onManageLiveShare = onManage,
                )
            }
        }
    }

    @Test
    fun notSharing_centreControlIsTheCreatePlus() {
        setBar(isSharing = false)

        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabCreate)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_liveControls)).assertDoesNotExist()
    }

    @Test
    fun sharing_centreControlBecomesTheLiveControl() {
        setBar(isSharing = true)

        // The whole point of the change: while a session runs there is no "+";
        // the centre control opens the live controls instead.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_liveControls)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabCreate)).assertDoesNotExist()
    }

    @Test
    fun notSharing_tappingTheCentreControlRaisesCreate() {
        var selected: ShellTab? = null
        var managed = false
        setBar(isSharing = false, onSelect = { selected = it }, onManage = { managed = true })

        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabCreate)).performClick()

        assertEquals(ShellTab.Create, selected)
        assertEquals(false, managed)
    }

    @Test
    fun sharing_tappingTheLiveControlRaisesManageAndNeverOpensCreate() {
        var selected: ShellTab? = null
        var managed = 0
        setBar(isSharing = true, onSelect = { selected = it }, onManage = { managed += 1 })

        composeTestRule.onNodeWithContentDescription(str(R.string.shell_liveControls)).performClick()

        assertEquals(1, managed)
        assertNull("Raising the live controls must not open the create chooser", selected)
    }
}
