package com.kungsbackacarcommunity.app.shell

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.crownhunt.PERK_DEPLOY_OPEN_SHOP_TAG
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.map.BrowsingInitialCamera
import com.kungsbackacarcommunity.app.map.MapZoomController
import com.kungsbackacarcommunity.app.map.MapZoomPreference
import com.kungsbackacarcommunity.app.map.MapZoomPreferenceStore
import com.kungsbackacarcommunity.app.redesign.MAP_CATALOGUE_FOOTER_TAG
import com.kungsbackacarcommunity.app.redesign.MAP_SHELL_CATALOGUE_CASES
import com.kungsbackacarcommunity.app.redesign.MapShellCataloguePreview
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full activity-window geometry and touch regression tests, not isolated sheet
 * previews. In particular a semantics performClick can bypass a covering window,
 * so footer reachability is proven with UiAutomation-injected touchscreen events
 * whose coordinates come from the live accessibility tree.
 * Local test fixtures only; no auth or production writes. Main owns execution.
 */
@RunWith(AndroidJUnit4::class)
class DockedMapShellInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun str(id: Int) = instrumentation.targetContext.getString(id)

    @Test
    fun everyMapPanelLeavesTheActualFooterTouchableAndDismissesOnTabSelection() {
        var screen by mutableStateOf(MAP_SHELL_CATALOGUE_CASES.first())
        val footerActions = AtomicInteger()
        val dismissals = AtomicInteger()
        rule.setContent {
            KccTheme {
                key(screen) {
                    MapShellCataloguePreview(screen,
                        onFooterAction = { if (it == ShellTab.Chat) footerActions.incrementAndGet() },
                        onSheetDismiss = { dismissals.incrementAndGet() })
                }
            }
        }
        MAP_SHELL_CATALOGUE_CASES.forEachIndexed { index, name ->
            rule.runOnIdle { screen = name }
            rule.waitForIdle()
            if (name == "map-session") physicalTap(str(R.string.shell_liveControls))
            awaitSheet()
            assertPanelClearsFooter(name)
            physicalTap(str(R.string.shell_tabChat))
            rule.waitUntil(5_000) { footerActions.get() == index + 1 && dismissals.get() == index + 1 }
            rule.onNodeWithTag(MAP_DETAIL_SHEET_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun narrowLargeTextKeepsTheLastSavedPlaceReachableAndFooterUncovered() {
        var selected: String? = null
        rule.setContent {
            KccTheme {
                NarrowLargeText {
                    MapShellCataloguePreview("map-saved", onLocalAction = { selected = it })
                }
            }
        }
        awaitSheet()
        val last = rule.onNodeWithTag(savedPlacePickerRowTestTag("qa-saved-7"))
        last.performScrollTo().assertIsDisplayed()
        assertPanelClearsFooter("saved at 320dp / 1.5x")
        physicalTap("Testplats 8")
        rule.runOnIdle { assertEquals("saved:qa-saved-7", selected) }
        rule.onNodeWithTag(MAP_DETAIL_SHEET_TAG).assertDoesNotExist()
    }

    @Test
    fun narrowLargeTextKeepsReportCreateAndPerkEndActionsReachable() {
        val cases = listOf("map-report", "map-create", "map-perks")
        var screen by mutableStateOf(cases.first())
        val dismissals = AtomicInteger()
        rule.setContent {
            KccTheme {
                NarrowLargeText {
                    key(screen) {
                        MapShellCataloguePreview(screen, onSheetDismiss = { dismissals.incrementAndGet() })
                    }
                }
            }
        }
        cases.forEachIndexed { index, name ->
            rule.runOnIdle { screen = name }
            awaitSheet()
            val target = when (name) {
                "map-report" -> rule.onNodeWithText(str(R.string.incidents_cancel))
                "map-create" -> rule.onNodeWithText(str(R.string.shell_createChooserConvoy))
                else -> rule.onNodeWithTag(PERK_DEPLOY_OPEN_SHOP_TAG)
            }
            target.performScrollTo().assertIsDisplayed()
            assertPanelClearsFooter("$name at 320dp / 1.5x")
            // Footer touch must still work after scrolling to the end, not just
            // in the sheet's initial collapsed scroll position.
            physicalTap(str(R.string.shell_tabChat))
            rule.waitUntil(5_000) { dismissals.get() == index + 1 }
            rule.onNodeWithTag(MAP_DETAIL_SHEET_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun appearanceChipsAreSelectionsAndFocusDistanceIsAdjustableAtLargeText() {
        val controller = RecordingZoomController(14.5)
        val surface = StubMapSurface(MapLoadState.Loaded, autoLoad = false)
        rule.setContent {
            KccTheme(darkTheme = true) {
                NarrowLargeText {
                    MapShellCataloguePreview("map-layers", surface, controller)
                }
            }
        }
        awaitSheet()
        val system = rule.onNode(hasText(str(R.string.shell_layersAppearanceSystem)) and hasClickAction())
        system.performScrollTo().assertIsSelected()
        val night = rule.onNodeWithTag(MAP_HOME_LAYERS_NIGHT_TAG)
        night.performScrollTo().performClick().assertIsSelected()
        system.assertIsNotSelected()
        // These are mutually-exclusive choices, not the former on/off night toggle.
        night.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
        rule.onNode(hasText(str(R.string.shell_layersAppearanceDay)) and hasClickAction())
            .performScrollTo().performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(MapMode.Day, surface.mapMode.value) }
        system.performScrollTo().performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(MapMode.Night, surface.mapMode.value) }
        rule.onNodeWithTag("map_layers_focus_expand").performScrollTo().performClick()
        rule.onNodeWithTag(MAP_HOME_LAYERS_ZOOM_TAG).performScrollTo().assertIsDisplayed()
            .performTouchInput { click(Offset(size.width * 0.75f, size.height / 2f)) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue("The actual slider must invoke its completion callback", controller.browsingZoom > 14.5)
            assertEquals(controller.browsingZoom, surface.browsingZoom, 0.0)
        }
        assertPanelClearsFooter("layers at 320dp / 1.5x")
    }

    @Test
    fun savedZoomSeedsTheColdBrowsingCameraAndIsReappliedToANewMapSurface() {
        // Keep this device-local persistence test out of the installed app's prefs.
        val context = IsolatedZoomContext(instrumentation.targetContext)
        try {
            MapZoomPreferenceStore(context).set(13.5)
            val reopened = MapZoomPreferenceStore(context)
            assertEquals(13.5, reopened.browsingZoom.value, 0.0)
            // This is the synchronous camera factory used before a new GPS fix;
            // a later LaunchedEffect-only assertion would miss the reported bug.
            assertEquals(13.5, BrowsingInitialCamera.resolve(null, reopened.browsingZoom.value).zoom, 0.0)
            var generation by mutableStateOf(0)
            val surfaces = List(2) { StubMapSurface(MapLoadState.Loaded, autoLoad = false) }
            rule.setContent {
                KccTheme {
                    key(generation) {
                        MapShellCataloguePreview("map-session", surfaces[generation],
                            RecordingZoomController(MapZoomPreferenceStore(context).browsingZoom.value))
                    }
                }
            }
            rule.runOnIdle { assertEquals(13.5, surfaces[0].browsingZoom, 0.0) }
            rule.runOnIdle { generation = 1 }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(13.5, surfaces[1].browsingZoom, 0.0) }
        } finally {
            context.clear()
        }
    }

    @Test
    fun liveSessionDockSitsAtTheBottomAndOpensTheProductionManageSheet() {
        val dismissals = AtomicInteger()
        val footerActions = AtomicInteger()
        rule.setContent {
            KccTheme {
                MapShellCataloguePreview("map-session",
                    onSheetDismiss = { dismissals.incrementAndGet() },
                    onFooterAction = { if (it == ShellTab.Chat) footerActions.incrementAndGet() })
            }
        }
        val dock = rule.onNodeWithTag("map_home_session_dock").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val footer = rule.onNodeWithTag(MAP_CATALOGUE_FOOTER_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("Live dock must clear the real footer", dock.bottom <= footer.top + 1f)
        assertTrue("Live dock must be bottom-anchored, not left in the top search row", footer.top - dock.bottom <= 2f)
        physicalTap(str(R.string.liveLocation_stop))
        awaitSheet()
        assertPanelClearsFooter("live management")
        physicalTap(str(R.string.shell_tabChat))
        rule.runOnIdle {
            assertEquals(1, footerActions.get())
            assertEquals(1, dismissals.get())
        }
    }

    private fun awaitSheet() {
        rule.waitUntil(5_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, MAP_DETAIL_SHEET_TAG))
                .fetchSemanticsNodes().size == 1
        }
        rule.waitForIdle()
    }

    private fun assertPanelClearsFooter(case: String) {
        val sheet = rule.onNodeWithTag(MAP_DETAIL_SHEET_TAG).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val footer = rule.onNodeWithTag(MAP_CATALOGUE_FOOTER_TAG).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("$case: sheet $sheet overlaps footer $footer", sheet.bottom <= footer.top + 1f)
        assertTrue("$case: empty or offscreen footer", footer.height > 0f && footer.width > 0f)
    }

    private fun physicalTap(label: String) {
        rule.waitForIdle()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100L, 5_000L)
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val node = checkNotNull(findVisibleNode(root, label)) { "No visible native accessibility node: $label" }
        val bounds = Rect().also(node::getBoundsInScreen)
        check(!bounds.isEmpty) { "Empty accessibility bounds for $label" }
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("OS rejected touch for $label", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
        rule.waitForIdle()
    }

    private fun findVisibleNode(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && (node.text?.toString()?.lines()?.contains(label) == true ||
                node.contentDescription?.toString() == label)) return node
        repeat(node.childCount) { index ->
            node.getChild(index)?.let { child -> findVisibleNode(child, label)?.let { return it } }
        }
        return null
    }

    @Composable
    private fun NarrowLargeText(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
            Box(Modifier.width(320.dp).fillMaxHeight()) { content() }
        }
    }

    private class RecordingZoomController(initial: Double) : MapZoomController {
        override var browsingZoom by mutableStateOf(initial)
            private set
        override fun setBrowsingZoom(zoom: Double) { browsingZoom = MapZoomPreference.snap(zoom) }
    }

    private class IsolatedZoomContext(base: Context) : ContextWrapper(base) {
        private val names = mutableSetOf<String>()
        private val prefix = "map-shell-qa-${System.nanoTime()}-"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
            val isolated = prefix + name
            names.add(isolated)
            return super.getSharedPreferences(isolated, mode)
        }
        fun clear() { names.forEach { super.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() } }
    }
}
