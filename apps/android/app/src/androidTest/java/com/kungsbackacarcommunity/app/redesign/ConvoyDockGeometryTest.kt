package com.kungsbackacarcommunity.app.redesign

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.convoy.*
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local fixtures only: measured dock geometry and OS-dispatched footer taps. */
@RunWith(AndroidJUnit4::class)
class ConvoyDockGeometryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun rosterKeepsFooterVisibleAndPhysicallyTappable() = checkDock(false)
    @Test fun memberActionsKeepFooterVisibleAndPhysicallyTappable() = checkDock(true)

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun checkDock(openActions: Boolean) {
        var selected: ShellTab? = null
        rule.setContent { KccTheme { MapShellCataloguePreview("map-convoy", onFooterAction = { selected = it }) } }
        rule.waitForIdle()
        val footer = bounds(MAP_CATALOGUE_FOOTER_TAG)
        val dock = bounds("map_home_session_dock")
        val stats = bounds(LIVE_SESSION_BAR_TEST_TAG)
        val convoy = bounds(CONVOY_BAR_TEST_TAG)
        assertTrue("statistics end before convoy controls", stats.bottom <= convoy.top + 1f)
        assertTrue("dock ends before footer", dock.bottom <= footer.top + 1f)
        assertTrue("convoy ends before footer", convoy.bottom <= footer.top + 1f)
        val density = rule.activity.resources.displayMetrics.density
        val controls = listOf(CONVOY_BAR_MEMBERS_TAG, CONVOY_BAR_FOCUS_TAG,
            CONVOY_BAR_CHAT_TAG, CONVOY_BAR_INVITE_TAG, CONVOY_BAR_LEAVE_TAG).map { tag ->
            rule.onNodeWithTag(tag).assertIsDisplayed()
            bounds(tag).also {
                assertTrue("$tag touch height", it.height >= 48f * density - 1f)
                assertTrue("$tag touch width", it.width >= 48f * density - 1f)
                assertTrue("$tag inside bar", it.left >= convoy.left && it.right <= convoy.right &&
                    it.top >= convoy.top && it.bottom <= convoy.bottom)
            }
        }
        for (a in controls.indices) for (b in a + 1 until controls.size) {
            assertTrue("convoy controls must not overlap", !controls[a].overlaps(controls[b]))
        }
        for (tag in listOf(MAP_HOME_REPORT_TAG, MAP_HOME_LAYERS_TAG, MAP_HOME_COMPASS_TAG,
            MAP_HOME_SAVED_PLACES_TAG)) {
            rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            assertTrue("$tag stays above measured dock", bounds(tag).bottom <= dock.top + 1f)
        }
        rule.onNodeWithTag(CONVOY_BAR_MEMBERS_TAG).performClick()
        rule.waitForIdle()
        if (openActions) {
            rule.onNodeWithText("Testförare 1").performClick()
            rule.waitForIdle()
        }
        val panel = if (openActions) CONVOY_BAR_MEMBER_ACTIONS_TAG else CONVOY_BAR_MEMBER_LIST_TAG
        rule.onNodeWithTag(panel).assertIsDisplayed()
        assertTrue("reading panel stays above footer", bounds(panel).bottom <= footer.top + 1f)

        // Bypass Compose performClick: a native popup/modal window could otherwise
        // obscure the footer while its underlying semantics action still passes.
        val label = rule.activity.getString(R.string.shell_tabChat)
        val point = rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        val origin = IntArray(2)
        rule.runOnIdle { rule.activity.findViewById<View>(android.R.id.content).getLocationOnScreen(origin) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                point.x + origin[0], point.y + origin[1], 0)
            try { assertTrue("OS accepts footer tap", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        rule.waitUntil(5_000) { selected == ShellTab.Chat }
        rule.onNodeWithTag(panel).assertDoesNotExist()
    }
}
