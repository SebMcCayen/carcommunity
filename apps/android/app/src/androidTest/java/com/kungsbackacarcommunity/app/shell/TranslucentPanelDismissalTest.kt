package com.kungsbackacarcommunity.app.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The translucent shell panels' four ways to dismiss: the accessibility
 * `dismiss` action, Back, tapping the uncovered map strip, and pulling the drag
 * handle down past its threshold (with the short-pull spring-back as the other
 * half of that threshold). Panel geometry is covered in
 * [TranslucentPanelLayoutTest]. Part of the map-first shell suite split out of
 * `MapFirstShellTest` (see [MapFirstShellTestSupport]).
 */
@RunWith(AndroidJUnit4::class)
class TranslucentPanelDismissalTest : MapFirstShellTestSupport() {

    /**
     * The accessibility escape hatch. A drag is unusable for a lot of people, so
     * the card carries a semantics `dismiss` action that closes the panel with no
     * gesture at all — this asserts the action is really wired to the dismissal,
     * not merely declared.
     */
    @Test
    fun panelDismissAction_closesThePanel_withoutAnyDrag() {
        setShell()
        openTab(R.string.shell_tabGarage)
        composeTestRule.onNodeWithTag(GARAGE_PANEL_TEST_TAG).assertIsDisplayed()

        composeTestRule
            .onNodeWithTag(GARAGE_PANEL_TEST_TAG)
            .performSemanticsAction(SemanticsActions.Dismiss)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(GARAGE_PANEL_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
    }

    /**
     * Back is the other non-drag dismissal (and the one most users reach for).
     * The shell's own handler returns to the Map tab from any panel tab.
     */
    @Test
    fun back_closesThePanel_withoutAnyDrag() {
        setShell()
        openTab(R.string.shell_tabSocial)
        composeTestRule.onNodeWithTag(SOCIAL_PANEL_TEST_TAG).assertIsDisplayed()

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SOCIAL_PANEL_TEST_TAG).assertDoesNotExist()
    }

    /**
     * Tapping the uncovered strip of map above the card dismisses the panel, the
     * same way it does for the chat hub. The tap point is DERIVED from the
     * measured card top and the device density rather than a magic pixel offset —
     * bounds are in dp and touch input is in px, so a fixed offset could land
     * outside the strip on a low-density device. Midway between the window top
     * and the card's top edge is provably inside the strip at any density; y is
     * negative because it is node-relative and the strip sits above the card.
     */
    @Test
    fun tappingTheUncoveredMapStrip_dismissesThePanel() {
        setShell()
        openTab(R.string.shell_tabHistory)
        val cardTop =
            composeTestRule.onNodeWithTag(HISTORY_PANEL_TEST_TAG).getUnclippedBoundsInRoot().top
        assertTrue("no uncovered strip to tap", cardTop > 16.dp)

        val stripMidYPx = with(composeTestRule.density) { cardTop.toPx() } / 2f
        composeTestRule.onNodeWithTag(HISTORY_PANEL_TEST_TAG).performTouchInput {
            click(Offset(width / 2f, -stripMidYPx))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HISTORY_PANEL_TEST_TAG).assertDoesNotExist()
    }

    /**
     * The gesture Seb asked for: pull the handle at the top of the panel DOWNWARDS
     * and the panel goes away.
     *
     * Swiped on the HANDLE specifically — it sits outside the page's scroll
     * container, so this exercises the `draggable` path rather than the
     * nested-scroll one, and covers a distance well past the dismiss threshold
     * (0.35 of the card height).
     */
    @Test
    fun pullingTheHandleDown_dismissesThePanel() {
        setShell()
        openTab(R.string.shell_tabGarage)
        val cardBounds =
            composeTestRule.onNodeWithTag(GARAGE_PANEL_TEST_TAG).getUnclippedBoundsInRoot()
        val cardHeightPx =
            with(composeTestRule.density) { (cardBounds.bottom - cardBounds.top).toPx() }

        composeTestRule.onNodeWithTag(PANEL_DRAG_HANDLE_TEST_TAG).performTouchInput {
            // Comfortably past the threshold, and slowly enough (a long
            // durationMillis) that it is the DISTANCE deciding this, not a fling.
            swipeDown(
                startY = center.y,
                endY = center.y + cardHeightPx * 0.8f,
                durationMillis = 600L,
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(GARAGE_PANEL_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
    }

    /**
     * The other half of the threshold, and the reason it exists: a SHORT pull is
     * an accident, and the panel must spring back rather than throwing away the
     * page the user was reading. An implementation that dismisses on any downward
     * movement passes the test above and fails this one.
     */
    @Test
    fun aShortPullOnTheHandle_leavesThePanelOpen() {
        setShell()
        openTab(R.string.shell_tabGarage)
        val cardBounds =
            composeTestRule.onNodeWithTag(GARAGE_PANEL_TEST_TAG).getUnclippedBoundsInRoot()
        val cardHeightPx =
            with(composeTestRule.density) { (cardBounds.bottom - cardBounds.top).toPx() }

        composeTestRule.onNodeWithTag(PANEL_DRAG_HANDLE_TEST_TAG).performTouchInput {
            // A tenth of the card, well under the 0.35 threshold, and slow
            // enough not to register as a flick.
            swipeDown(
                startY = center.y,
                endY = center.y + cardHeightPx * 0.1f,
                durationMillis = 600L,
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(GARAGE_PANEL_TEST_TAG).assertIsDisplayed()
    }
}
