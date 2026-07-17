package com.kungsbackacarcommunity.app.chatchannels

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue 3: tapping "Write a message" threw the chat input almost to the top of the
 * screen instead of parking it just above the keyboard.
 *
 * ROOT CAUSE, measured on API 34: the chat hub was presented as a
 * [androidx.compose.ui.window.Popup], and a Popup gets its OWN window which
 * receives NO window-inset dispatch. `WindowInsets.navigationBars` read inside the
 * popup reported 0 at the very moment the host activity window reported the real
 * inset (63px), and `WindowInsets.ime` was likewise pinned at 0. So the
 * `WindowInsets.ime.union(WindowInsets.navigationBars)` padding PR #427 put on the
 * message input evaluated to ZERO inside the hub: the input sat flush on the
 * window's bottom edge (under the nav bar), and with no app-side IME handling the
 * framework's legacy ADJUST_PAN — the popup window's softInputMode defaulted to
 * SOFT_INPUT_ADJUST_UNSPECIFIED — panned the whole window when the keyboard rose.
 *
 * The fix hosts the hub in the ACTIVITY's window, which `enableEdgeToEdge()`
 * already puts in charge of its own insets.
 *
 * These tests pin that root cause structurally rather than trying to photograph the
 * keyboard: the CI emulator will not raise a soft IME under instrumentation
 * (`mInputShown` stays false even for a focused `EditText` with `SHOW_FORCED`), so
 * a keyboard-up pixel assertion would be untrustworthy. What is both testable and
 * decisive is that the hub composes in the host window rather than a window of its
 * own — because a window of its own is exactly what zeroed the insets, and once the
 * hub is in the host window #427's `ime.union(navigationBars)` expression works in
 * both keyboard states by construction.
 */
@RunWith(AndroidJUnit4::class)
class ChatHubInsetsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val HOST_TAG = "host_root_probe"
    }

    /**
     * The decisive structural claim: the hub composes into the HOST window, not a
     * window of its own.
     *
     * Compose gives each window its own composition root, so comparing the hub's
     * root with a node known to live in the host content distinguishes the two
     * presentations exactly. Against the pre-fix (Popup) hub the roots differ and
     * this fails — and a differing root is precisely what zeroed the hub's insets.
     */
    @Test
    fun chatHubComposesInTheHostWindowNotItsOwn() {
        composeTestRule.activity.runOnUiThread { composeTestRule.activity.enableEdgeToEdge() }
        composeTestRule.setContent {
            KccTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.testTag(HOST_TAG))
                    ChatHubPopup(
                        uid = "u1",
                        communityChatRepository = null,
                        convoyChatRepository = null,
                        friendsRepository = null,
                        dmRepository = null,
                        notificationsRepository = null,
                        notificationsCoordinator = null,
                        communityUnread = false,
                        onClose = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val hostRoot = composeTestRule.onNodeWithTag(HOST_TAG).fetchSemanticsNode().root
        val hubRoot = composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).fetchSemanticsNode().root
        Log.w("ChatHubInsets", "hostRoot=$hostRoot hubRoot=$hubRoot")

        assertSame(
            "The chat hub must compose in the host window's root. A different root " +
                "means it is in its own window (a Popup) — which receives no window " +
                "insets, so the message input's ime.union(navigationBars) padding " +
                "resolves to 0 and the IME handling falls back to ADJUST_PAN.",
            hostRoot,
            hubRoot,
        )
    }

    /**
     * Keyboard-DOWN (#427's fix, which must not regress): the union the message
     * input pads by is exactly the navigation-bar inset — so the input clears the
     * nav bar instead of hiding behind it.
     *
     * HONEST SCOPE: this probe composes as a sibling of the hub, so it necessarily
     * reads the HOST window, and it passes against the pre-fix Popup form too — on
     * its own it has no teeth. It is only meaningful PAIRED with
     * [chatHubComposesInTheHostWindowNotItsOwn], which is what establishes that the
     * hub's own content resolves these same insets. Together they say: the hub is in
     * the host window, and that window's `ime.union(navigationBars)` is the nav-bar
     * inset with the keyboard down.
     *
     * Reading the insets from strictly INSIDE the hub's subtree would need a
     * production-only test hook through several private layers; the structural test
     * pins the same root cause without one.
     *
     * SKIPPED on a device with no navigation bar (the CI emulator reports
     * `navigationBars = 0`): there, a passing union of 0 is indistinguishable from
     * the popup's broken zero, so the assertion would be vacuous. Skipping says so
     * out loud instead of banking a green tick that proves nothing — the structural
     * test above still runs everywhere and carries the teeth.
     */
    @Test
    fun keyboardDown_theInputsBottomPaddingIsTheNavigationBarInset() {
        var nav = -1
        var ime = -1
        var union = -1
        composeTestRule.activity.runOnUiThread { composeTestRule.activity.enableEdgeToEdge() }
        composeTestRule.setContent {
            KccTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    ChatHubPopup(
                        uid = "u1",
                        communityChatRepository = null,
                        convoyChatRepository = null,
                        friendsRepository = null,
                        dmRepository = null,
                        notificationsRepository = null,
                        notificationsCoordinator = null,
                        communityUnread = false,
                        onClose = {},
                    )
                    // Composed in the same window the hub now lives in, so this reads
                    // the very insets the hub's message input resolves.
                    val density = LocalDensity.current
                    val n = WindowInsets.navigationBars.getBottom(density)
                    val i = WindowInsets.ime.getBottom(density)
                    val u = WindowInsets.ime.union(WindowInsets.navigationBars).getBottom(density)
                    LaunchedEffect(n, i, u) {
                        nav = n
                        ime = i
                        union = u
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        Log.w("ChatHubInsets", "keyboard-down: nav=$nav ime=$ime union=$union")
        // Not a failure — an environment that cannot exercise the claim. A union of
        // 0 on a device with no nav bar is correct AND is what the broken popup
        // produced, so asserting it would bank a meaningless pass.
        assumeTrue(
            "Skipped: this device reports no navigation-bar inset ($nav), so a " +
                "union of 0 here is indistinguishable from the popup's broken zero.",
            nav > 0,
        )
        assertEquals("No IME is up, so the IME inset must be 0.", 0, ime)
        assertEquals(
            "Keyboard down, the input must pad by the nav-bar inset (PR #427) — a " +
                "union of 0 puts it back under the nav bar.",
            nav,
            union,
        )
    }
}
