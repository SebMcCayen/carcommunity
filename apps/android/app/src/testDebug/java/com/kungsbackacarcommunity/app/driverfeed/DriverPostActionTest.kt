package com.kungsbackacarcommunity.app.driverfeed

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.kungsbackacarcommunity.app.chatchannels.driverFeedStrings
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "sv-rSE-w411dp-h1200dp-mdpi")
class DriverPostActionTest {
    @get:Rule val compose = createComposeRule()
    private val post = DriverPost("post-a", "author", "Author", "member", "Post A", null, emptyList(), 0, 0)

    @Test fun everyFeedPostHasAnAppActionAndTheCardStillOpensItsExactNativeDetail() {
        var appOpens = 0
        var selected: String? = null
        show(DriverFeedState(posts = listOf(post, post.copy(id = "post-b", text = "Post B"))),
            onOpen = { selected = it }, onOpenDriver = { appOpens++; true })

        compose.onAllNodesWithText("Öppna Driver-appen").assertCountEquals(2)
        compose.runOnIdle { assertEquals(0, appOpens) }
        compose.onAllNodesWithText("Öppna Driver-appen")[0].performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, appOpens)
            assertEquals(null, selected) // The nested action must not also trigger the card.
        }
        compose.onAllNodesWithText("Öppna Driver-appen")[1].performScrollTo().performClick()
        compose.onNodeWithText("Post B").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, appOpens)
            assertEquals("post-b", selected)
        }
    }

    @Test fun detailExplainsTheLinkLimitAndHandlesFailedLaunchWithRetry() {
        var appOpens = 0
        show(DriverFeedState(selectedId = post.id, post = post),
            onOpenDriver = { ++appOpens > 1 })

        compose.onNodeWithText("Driver kan ännu inte öppna ett specifikt inlägg via länk.")
            .performScrollTo().assertIsDisplayed()
        val action = compose.onNodeWithText("Öppna Driver-appen")
        action.performScrollTo().performClick()
        val error = compose.onNodeWithText(
            "Driver kunde inte öppnas. Ingen publicerad nedladdningslänk finns tillgänglig ännu.")
        error.performScrollTo().assertIsDisplayed()
        action.performScrollTo().performClick()
        error.assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, appOpens) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h1200dp-mdpi")
    fun englishCopyResolvesThroughTheProductionStringAdapter() {
        show(DriverFeedState(selectedId = post.id, post = post), onOpenDriver = { false })
        compose.onNodeWithText("Open the Driver app").performScrollTo().performClick()
        compose.onNodeWithText("Driver does not yet support links to a specific post.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Driver could not be opened. No published download link is available yet.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun defaultProductionActionHandlesTheAndroidMissingHandler() {
        var launched: Intent? = null
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun startActivity(intent: Intent) {
                launched = intent
                throw ActivityNotFoundException()
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                KccTheme {
                    DriverFeedContent(DriverFeedState(posts = listOf(post)), driverFeedStrings(),
                        onBack = {}, onRefresh = {}, onMore = {}, onOpen = {})
                }
            }
        }
        compose.runOnIdle { assertEquals(null, launched) }
        compose.onNodeWithText("Öppna Driver-appen").performScrollTo().performClick()
        compose.onNodeWithText(
            "Driver kunde inte öppnas. Ingen publicerad nedladdningslänk finns tillgänglig ännu.")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(DRIVER_ANDROID_PACKAGE, requireNotNull(launched).`package`) }
    }

    private fun show(state: DriverFeedState, onOpen: (String) -> Unit = {}, onOpenDriver: () -> Boolean) {
        compose.setContent {
            KccTheme {
                // Production string adapter must resolve every new canonical key.
                DriverFeedContent(state, driverFeedStrings(), onBack = {}, onRefresh = {}, onMore = {},
                    onOpen = onOpen, onOpenDriver = onOpenDriver)
            }
        }
    }
}
