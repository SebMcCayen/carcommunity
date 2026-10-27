package com.kungsbackacarcommunity.app.navigation.turnbyturn

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w650dp-h1000dp-mdpi")
class NavChromeRegionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shortWindowsAndLargeTextKeepBothRegionsInsideDisjointViewports() {
        val height = mutableStateOf(180)
        val scale = mutableStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
                KccTheme {
                    Box(Modifier.size(320.dp, height.value.dp).testTag("window")) {
                        NavChromeRegions(top = { Actions("top") }, bottom = { Actions("bottom") })
                    }
                }
            }
        }
        // Reuse the layout through growth and shrinkage: no remembered height
        // from the previous window may grant the other region too much space.
        for (windowHeight in listOf(180, 320, 720, 180)) for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle {
                height.value = windowHeight
                scale.value = fontScale
            }
            assertRegionsDisjoint()
        }
    }

    @Test
    fun contentGrowthReallocatesSpaceAndSmallChromeRetainsItsNaturalHeight() {
        val tall = mutableStateOf(false)
        compose.setContent {
            Box(Modifier.size(320.dp).testTag("window")) {
                NavChromeRegions(
                    top = { Box(Modifier.fillMaxWidth().height(if (tall.value) 600.dp else 48.dp)) },
                    bottom = { Box(Modifier.fillMaxWidth().height(600.dp)) },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(48f, bounds(NAV_CHROME_TOP_REGION_TAG).height, 0.5f)
        assertEquals(272f, bounds(NAV_CHROME_BOTTOM_REGION_TAG).height, 0.5f)
        compose.runOnIdle { tall.value = true }
        assertRegionsDisjoint()
        assertEquals(144f, bounds(NAV_CHROME_TOP_REGION_TAG).height, 0.5f)
        assertEquals(176f, bounds(NAV_CHROME_BOTTOM_REGION_TAG).height, 0.5f)
        compose.runOnIdle { tall.value = false }
        assertRegionsDisjoint()
        assertEquals(272f, bounds(NAV_CHROME_BOTTOM_REGION_TAG).height, 0.5f)
    }

    @Test
    fun bothScrollRegionsExposeActionsAndKeepEveryControlReachable() {
        val clicks = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                KccTheme {
                    Box(Modifier.size(320.dp, 180.dp).testTag("window")) {
                        NavChromeRegions(
                            top = { Actions("top") { clicks += it } },
                            bottom = { Actions("bottom") { clicks += it } },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        // The final bottom action represents ETA/Exit: visible before scrolling.
        compose.onNodeWithTag("bottom-7").assertIsDisplayed()
        for (region in listOf(NAV_CHROME_TOP_REGION_TAG, NAV_CHROME_BOTTOM_REGION_TAG)) {
            compose.onNodeWithTag(region).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
        }
        val actions = listOf("top-7", "top-0", "bottom-0", "bottom-7")
        for (tag in actions) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
            assertRegionsDisjoint()
        }
        assertEquals(actions, clicks)
    }

    @Test
    fun unoccupiedMiddlePassesTouchesToTheMap() {
        var mapClicks = 0
        compose.setContent {
            Box(Modifier.size(320.dp).testTag("window")) {
                Box(Modifier.fillMaxSize().testTag("map").clickable { mapClicks++ })
                NavChromeRegions(
                    top = { Box(Modifier.fillMaxWidth().height(48.dp)) },
                    bottom = { Box(Modifier.fillMaxWidth().height(60.dp)) },
                )
            }
        }
        compose.onNodeWithTag("map").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, mapClicks) }
        assertRegionsDisjoint()
    }

    @Test
    fun actualLiveAndConvoyBarsCanShareTheScrollableTopRegion() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                KccTheme {
                    Box(Modifier.size(320.dp, 180.dp).testTag("window")) {
                        NavChromeRegions(
                            top = {
                                Column {
                                    com.kungsbackacarcommunity.app.shell.LiveSessionBar(0L, 123_400.0,
                                        speedSample = null, now = { 21_540_000L })
                                    com.kungsbackacarcommunity.app.convoy.ConvoyStatusBar(
                                        state = com.kungsbackacarcommunity.app.convoy.ConvoyBarState(
                                            convoyId = "local", members = listOf(
                                                com.kungsbackacarcommunity.app.convoy.ConvoyBarMember("local", "Local", null)),
                                            viewerIsOwner = true, busy = false,
                                            inviteAvailability = com.kungsbackacarcommunity.app.convoy.ConvoyBar.inviteAvailability,
                                            leaveAvailability = com.kungsbackacarcommunity.app.convoy.ConvoyBar.leaveAvailability),
                                        onEndConvoy = {}, compact = true, showDestination = false)
                                }
                            },
                            bottom = { Actions("bottom") },
                        )
                    }
                }
            }
        }
        assertRegionsDisjoint()
        compose.onNodeWithTag(com.kungsbackacarcommunity.app.convoy.CONVOY_BAR_MEMBERS_TAG)
            .performScrollTo().assertIsDisplayed()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertRegionsDisjoint() {
        compose.waitForIdle()
        val window = bounds("window")
        val top = bounds(NAV_CHROME_TOP_REGION_TAG)
        val bottom = bounds(NAV_CHROME_BOTTOM_REGION_TAG)
        assertTrue("Top is bounded: $top / $window", top.top >= window.top - 0.5f &&
            top.height <= window.height * 0.45f + 0.5f)
        assertTrue("Chrome cannot overlap: $top / $bottom", top.bottom <= bottom.top + 0.5f)
        assertTrue("Bottom stays inside window: $bottom / $window", bottom.bottom <= window.bottom + 0.5f)
        assertEquals(window.top, top.top, 0.5f)
        assertEquals(window.bottom, bottom.bottom, 0.5f)
        assertTrue("Both regions remain available", top.height > 0f && bottom.height > 0f)
    }

    @Composable
    private fun Actions(prefix: String, onAction: (String) -> Unit = {}) {
        Column {
            repeat(8) { index ->
                val tag = "$prefix-$index"
                TextButton(onClick = { onAction(tag) }, modifier = Modifier.testTag(tag)) {
                    Text("Action $index", fontSize = 16.sp)
                }
            }
        }
    }
}
