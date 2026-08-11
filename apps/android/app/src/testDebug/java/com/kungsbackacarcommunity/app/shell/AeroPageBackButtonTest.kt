package com.kungsbackacarcommunity.app.shell

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Robolectric-backed Compose UI test (fast, blocking `testDebugUnitTest`, no
 * emulator) for the pinned in-app Back arrow that [AeroPage] / [AeroLazyPage]
 * render for pushed sub-routes. Lives in `src/testDebug` for the same reason as
 * [com.kungsbackacarcommunity.app.onboarding.OnboardingScreenTest]: the
 * ComponentActivity host `createComposeRule()` launches into comes from
 * `ui-test-manifest`, a debug-only dependency.
 */
@RunWith(AndroidJUnit4::class)
class AeroPageBackButtonTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun pushedRoute_showsBackArrow_andTapFiresBackDispatcher() {
        var backInvocations = 0
        composeTestRule.setContent {
            KccTheme {
                // Register a back callback so the dispatcher the arrow invokes has a
                // handler to run — exactly how a pushed route's own BackHandler /
                // the shell's central BackHandler would receive it.
                val dispatcher =
                    LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                dispatcher.addCallback(
                    object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            backInvocations++
                        }
                    },
                )
                // Provided true == a page rendered under the shell's RouteHost.
                CompositionLocalProvider(LocalAeroBackAvailable provides true) {
                    AeroPage(title = "Settings") { Text("body") }
                }
            }
        }

        composeTestRule.onNodeWithTag(AeroBackButtonTag).assertIsDisplayed().performClick()
        assertEquals(1, backInvocations)
    }

    @Test
    fun tabRootOrPanel_doesNotShowBackArrow() {
        composeTestRule.setContent {
            KccTheme {
                // Default (false) == a tab root / translucent panel: it never goes
                // through RouteHost, so no arrow is provided.
                AeroPage(title = "Garage") { Text("body") }
            }
        }

        composeTestRule.onNodeWithTag(AeroBackButtonTag).assertDoesNotExist()
    }
}
