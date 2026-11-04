package com.kungsbackacarcommunity.app.design

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chatchannels.*
import com.kungsbackacarcommunity.app.redesign.MapShellCataloguePreview
import com.kungsbackacarcommunity.app.redesign.MAP_CATALOGUE_FOOTER_TAG
import com.kungsbackacarcommunity.app.shell.MAP_HOME_LAYERS_POPUP_TAG
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

/** Real system bars/IME, production styling and screens, disposable local content only. */
@RunWith(AndroidJUnit4::class)
class SystemBarsNativeTest {
    private val compose = createAndroidComposeRule<SystemBarsTestActivity>()
    @get:Rule val rules: RuleChain = if (Build.VERSION.SDK_INT >= 33) {
        RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(compose)
    } else {
        RuleChain.outerRule(compose)
    }
    private var dark by mutableStateOf(true)

    @Before fun configureRequestedOrientationBeforeContent() {
        val orientation = InstrumentationRegistry.getArguments().getString("orientation")
        val expected = when (orientation) {
            "portrait" -> Configuration.ORIENTATION_PORTRAIT
            "landscape" -> Configuration.ORIENTATION_LANDSCAPE
            else -> return
        }
        // Set this on the actual test host, after ActivityScenario has launched.
        // A shell rotation set before instrumentation can be reset by the host.
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (orientation == "landscape") {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == expected }
    }

    @Test fun themeSwitchKeepsIconContrastInsetsAndOneProtectionLayer() {
        compose.setContent {
            Style()
            KccTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Text("Lokalt kant-till-kant-test", Modifier.testTag("safe_content"))
                }
            }
        }
        for (mode in listOf(true, false, true, false)) {
            compose.runOnIdle { dark = mode }
            compose.waitForIdle()
            assertStyle(mode)
            val safe = compose.onNodeWithTag("safe_content").fetchSemanticsNode().boundsInRoot
            val insets = insets().getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            assertTrue("Text must clear status bar/cutout", safe.top >= insets.top)
            assertTrue("Text must clear side cutout", safe.left >= insets.left)
            capture("system-bars-${if (mode) "dark" else "light"}")
        }
    }

    @Test fun chatComposerClearsKeyboardAndReturnsWithoutLosingDraft() {
        compose.setContent {
            Style()
            KccTheme(darkTheme = dark) {
                ChatHubRoute(
                    uid = "local-insets-member", communityChatRepository = LocalChat,
                    convoyChatRepository = null, friendsRepository = null, dmRepository = null,
                    communityUnread = false, convoysUnread = false, friendsUnread = false,
                    onClose = {}, showHubBackArrow = true,
                    driverContent = { Text("Lokala Driver-inlägg") },
                )
            }
        }
        assertStyle(true)
        val input = compose.onNodeWithTag(CHANNEL_INPUT_TEST_TAG)
        input.assertIsDisplayed()
        capture("chat-before-keyboard")
        input.performClick().performTextInput("Utkast med åäö 🚗")
        compose.waitUntil(10_000) { insets().isVisible(WindowInsetsCompat.Type.ime()) }
        compose.onNodeWithTag(CHAT_CHANNEL_STRIP_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CHAT_SOURCE_SELECTOR_TEST_TAG).assertDoesNotExist()
        input.assertIsDisplayed().assertTextContains("Utkast med åäö 🚗")
        val rootHeight = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        val keyboard = insets().getInsets(WindowInsetsCompat.Type.ime()).bottom
        val inputBounds = input.fetchSemanticsNode().boundsInRoot
        assertTrue("Composer must stay above keyboard", inputBounds.bottom <= rootHeight - keyboard + 2f)
        capture("chat-keyboard-dark")
        Espresso.pressBack()
        compose.waitUntil(10_000) { !insets().isVisible(WindowInsetsCompat.Type.ime()) }
        compose.onNodeWithTag(CHAT_CHANNEL_STRIP_TEST_TAG).assertIsDisplayed()
        input.assertTextContains("Utkast med åäö 🚗")
        compose.runOnIdle { dark = false }
        compose.waitForIdle()
        assertStyle(false)
        capture("chat-keyboard-dismissed-light")
    }

    @Test fun mapSheetLeavesFooterActionsAboveSystemNavigation() {
        compose.setContent {
            Style()
            KccTheme(darkTheme = dark) { MapShellCataloguePreview("map-layers") }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (mode in listOf(true, false)) {
            compose.runOnIdle { dark = mode }
            compose.waitForIdle()
            assertStyle(mode)
            val panel = compose.onNodeWithTag(MAP_HOME_LAYERS_POPUP_TAG)
            panel.assertIsDisplayed()
            val footer = compose.onNodeWithTag(MAP_CATALOGUE_FOOTER_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue("Map panel must stay above footer", panel.fetchSemanticsNode().boundsInRoot.bottom <= footer.top + 2f)
            val rootHeight = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
            val bars = insets().getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
            for (label in listOf(R.string.shell_tabMap, R.string.shell_tabCreate)) {
                val button = if (label == R.string.shell_tabCreate) {
                    compose.onNodeWithContentDescription(context.getString(label))
                } else {
                    // NavigationBar merges the selected icon into its labelled tab.
                    compose.onNode(hasText(context.getString(label)) and hasClickAction())
                }
                button.assertIsDisplayed()
                val bounds = button.fetchSemanticsNode().boundsInRoot
                assertTrue("Footer action overlaps bottom navigation", bounds.bottom <= rootHeight - bars.bottom + 2f)
                assertTrue("Footer action overlaps left cutout/navigation", bounds.left >= bars.left)
                assertTrue("Footer action overlaps right cutout/navigation", bounds.right <= rootWidth - bars.right + 2f)
            }
            capture("map-layers-${if (mode) "dark" else "light"}")
        }
    }

    @androidx.compose.runtime.Composable private fun Style() {
        DisposableEffect(dark) {
            compose.activity.applyKccSystemBars(dark)
            onDispose { }
        }
    }

    private fun insets() = checkNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))

    private fun assertStyle(dark: Boolean) = compose.runOnIdle {
        val window = compose.activity.window
        val arguments = InstrumentationRegistry.getArguments()
        when (arguments.getString("orientation")) {
            "portrait" -> assertEquals(Configuration.ORIENTATION_PORTRAIT, compose.activity.resources.configuration.orientation)
            "landscape" -> assertEquals(Configuration.ORIENTATION_LANDSCAPE, compose.activity.resources.configuration.orientation)
        }
        val tappableInsets = insets().getInsets(WindowInsetsCompat.Type.tappableElement())
        val tappable = maxOf(tappableInsets.left, tappableInsets.right, tappableInsets.bottom)
        when (arguments.getString("navigationMode")) {
            "gesture" -> assertEquals("Gesture navigation must be active", 0, tappable)
            "threebutton" -> assertTrue("Three-button navigation must be active", tappable > 0)
        }
        if (arguments.getString("requireCutout") == "true") {
            val cutout = insets().getInsets(WindowInsetsCompat.Type.displayCutout())
            assertTrue("Cutout emulation must be active", cutout.top + cutout.left + cutout.right > 0)
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals(!dark, controller.isAppearanceLightStatusBars)
        assertEquals(!dark, controller.isAppearanceLightNavigationBars)
        if (Build.VERSION.SDK_INT >= 29) assertFalse(window.isNavigationBarContrastEnforced)
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, window.attributes.layoutInDisplayCutoutMode)
        }
        if (Build.VERSION.SDK_INT >= 35) {
            assertEquals("Theme changes must update, not stack, navigation scrims", 1, protectionCount(window.decorView))
        }
    }

    private fun protectionCount(view: View): Int =
        (if (view.javaClass.name == "androidx.core.view.insets.ProtectionLayout") 1 else 0) +
            (if (view is ViewGroup) (0 until view.childCount).sumOf { protectionCount(view.getChildAt(it)) } else 0)

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(checkNotNull(context.getExternalFilesDir(null)), "system-bars")
            check(dir.mkdirs() || dir.isDirectory)
            File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private object LocalChat : CommunityChatRepository {
        override fun observeMessages() = flowOf(ChannelMessagesState.Loaded(emptyList()))
        override fun observeUnread(uid: String) = flowOf(false)
        override suspend fun post(text: String, mentionedUids: List<String>, clientId: String?) =
            ChannelSendResult.Failed(ChannelSendError.Generic)
        override suspend fun loadOlder(before: String) = ChannelOlderResult.Failed
        override suspend fun markRead() = Unit
    }
}
