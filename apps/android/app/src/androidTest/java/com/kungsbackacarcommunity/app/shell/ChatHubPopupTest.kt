package com.kungsbackacarcommunity.app.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chatchannels.CHAT_HUB_TEST_TAG
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The floating chat hub: opening/dismissing it as a transparent popup over the
 * live map, the uncovered map strip above its card (and dismiss-by-tapping it),
 * that a tap inside the card does NOT dismiss, and that the hub never re-opens by
 * itself after leaving and returning to the Map tab. Part of the map-first shell
 * suite split out of `MapFirstShellTest` (see [MapFirstShellTestSupport]).
 */
@RunWith(AndroidJUnit4::class)
class ChatHubPopupTest : MapFirstShellTestSupport() {

    @Test
    fun chatBubble_opensAndDismissesChatHub() {
        setShell()
        // The floating chat bubble is present (unread count is 0 → "Chat").
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_chat)).assertExists()
        // Tapping it opens the chat hub as a TRANSPARENT popup over the
        // map (the map stays composed behind it, not a full opaque route).
        composeTestRule.onNodeWithTag(MAP_HOME_CHAT_TAG).performClick()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertIsDisplayed()
        // The map stays visible behind the transparent popup.
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
        // The Community / Convoys / Friends / Notifications tabs are shown.
        composeTestRule.onNodeWithText(str(R.string.chatHub_tabCommunity)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.chatHub_tabConvoys)).assertIsDisplayed()
        // The hub is a shared shell panel now: it carries the same labelled drag
        // handle every other panel does, and there is NO close (X) button — it is
        // dismissed by pulling that handle down, tapping the map strip, or Back.
        composeTestRule
            .onNodeWithContentDescription(str(R.string.shell_panelDragHandle))
            .assertExists()
        // The panel's accessibility `dismiss` action is the non-gesture route the
        // former X used to be; firing it returns to the map (the hub is gone).
        composeTestRule
            .onNodeWithTag(CHAT_HUB_TEST_TAG)
            .performSemanticsAction(SemanticsActions.Dismiss)
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
    }

    /**
     * Regression: the chat-hub popup card must be strictly SHORTER than the window,
     * leaving a real, visible, tappable strip of map above it — and a tap in that
     * strip must dismiss the hub. A full-height card (e.g. `fillMaxHeight()`, whose
     * preceding padding lands inside its own footprint) would leave no genuine
     * "outside" and make the dismiss layer unreachable.
     */
    @Test
    fun chatHubPopup_leavesUncoveredMapStripAboveCard_andTapThereDismisses() {
        setShell()
        composeTestRule.onNodeWithTag(MAP_HOME_CHAT_TAG).performClick()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertIsDisplayed()

        // The card is bottom-anchored, so an uncovered strip exists iff its top edge
        // sits strictly below the window top. A full-height card reports top=0 and
        // fails HERE, which is what gives this test its teeth. 16.dp is a floor for
        // "actually visible + tappable", not a mirror of the layout's tuning knob —
        // the production fraction stays private and is deliberately not asserted on.
        val cardTop =
            composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).getUnclippedBoundsInRoot().top
        assertTrue(
            "chat-hub card top was $cardTop — expected a strip of map above the card",
            cardTop > 16.dp,
        )

        // ...and that strip must clear system UI, not just be non-zero: the card's
        // top may never sit under the status bar, or the hub's own top bar renders
        // beneath it. This is what stops the height fraction from being taken
        // against the raw window — on a short window (landscape / split-screen) a
        // fraction of the WINDOW can be smaller than the status bar, while a
        // fraction of the SAFE AREA cannot.
        val statusBarTop = with(composeTestRule.density) { statusBarHeightPx().toDp() }
        assertTrue(
            "chat-hub card top was $cardTop — expected it to clear the " +
                "$statusBarTop status bar so the hub's top bar isn't under system UI",
            cardTop >= statusBarTop,
        )

        // That strip is the dismiss affordance: tap inside it and the hub closes
        // while the map stays. The tap point is DERIVED from the measured card top
        // and the device density rather than a fixed pixel offset — touch input is
        // in px while the bounds above are in dp, so a magic px offset could land
        // outside the strip (above the window) on a low-density device. Midway
        // between the window top and the card's top edge is provably inside the
        // strip at any density; y is negative because it is node-relative and the
        // strip sits above the card.
        val stripMidYPx = with(composeTestRule.density) { cardTop.toPx() } / 2f
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).performTouchInput {
            click(Offset(width / 2f, -stripMidYPx))
        }
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
    }

    /**
     * Regression: a tap INSIDE the chat-hub card must never dismiss the hub, even on
     * a spot no child consumes. The dismiss layer behind the card is `fillMaxSize()`,
     * so this pins that the card itself swallows the touch rather than letting it
     * fall through to that layer. Every repository is null in this configuration, so
     * the card's body is the non-interactive `TabPlaceholder` (a plain Box + Text) —
     * exactly the "empty area / placeholder" case.
     */
    @Test
    fun chatHubPopup_tapInsideCardOnNonInteractiveArea_doesNotDismiss() {
        setShell()
        composeTestRule.onNodeWithTag(MAP_HOME_CHAT_TAG).performClick()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertIsDisplayed()
        // Sanity: the body really is the non-interactive placeholder, not a live
        // channel with its own click handlers. Every section is null-repo here, so
        // each of the hub's swipe-pager pages renders this same placeholder text.
        // Match them all and assert the front one — the page on screen — rather
        // than requiring exactly one node, so this stays a statement about what the
        // member sees and not an incidental assertion about how many pages the
        // pager happens to keep composed.
        composeTestRule.onAllNodesWithText(str(R.string.chatHub_unavailable))
            .onFirst()
            .assertIsDisplayed()

        // Tap well inside the card, in the placeholder body: coordinates are derived
        // from the card's own measured size (node-relative px), not magic pixels.
        // 75% down clears the top bar and the tab row, so nothing interactive is
        // under the finger — if the card failed to consume the touch it would reach
        // the dismiss layer behind it and close the hub.
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).performTouchInput {
            click(Offset(width / 2f, height * 0.75f))
        }
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.chatHub_unavailable))
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * Regression: the chat hub must not re-open by itself. `chatHubOpen` is
     * rememberSaveable but the hub only renders while the map shell is the active
     * branch, so losing that gate has to CLEAR the flag — otherwise the hub
     * silently stays "open" and pops up again on returning to the map.
     *
     * The hub is dismissed via the map strip BEFORE switching tabs, because that is
     * the only way a user can leave the Map tab: the hub's card covers the bottom
     * nav bar, so a tap on a tab lands on the card and is inert. That was equally
     * true of the previous Popup presentation — that popup window was touch-modal
     * (its flags carried no FLAG_NOT_TOUCH_MODAL), so it swallowed the tap the same
     * way. Only a test-injected click reached the tab, because Compose dispatches it
     * straight to the node's own window and so bypassed the popup entirely; now that
     * the hub composes in the host window (see ChatHubInsetsTest for why it must),
     * the injected click meets the same card a finger does. The old sequence
     * therefore asserted a path no user could take.
     */
    @Test
    fun chatHub_doesNotReappearAfterLeavingAndReturningToMapTab() {
        setShell()
        // Open the hub over the map.
        composeTestRule.onNodeWithTag(MAP_HOME_CHAT_TAG).performClick()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertIsDisplayed()

        // A tab tap while the hub is open is inert — the card is in the way. This
        // genuinely models a physical tap: performClick() is not a semantics-action
        // shortcut, it delegates to performTouchInput { click() } (ActionsKt
        // .performClick -> Actions_androidKt.performClickImpl -> performTouchInput),
        // so the event is hit-tested against whatever is actually on top. If the card
        // did not block it, the shell would switch tabs and the hub would vanish.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabHistory)).performClick()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()

        // Dismiss the hub the way a user must: tap the uncovered strip of live map
        // ABOVE the card. The tap is dispatched on the map-home node at a positive,
        // in-bounds offset — never on the hub node with a negative Y to reach
        // "outside" its own bounds, which relies on out-of-bounds dispatch and is
        // brittle. The Y is still DERIVED, not magic: it is the midpoint between the
        // map's top and the card's measured top, converted through the test density,
        // so it is provably inside the strip at any density or window size.
        val cardTopPx =
            with(composeTestRule.density) {
                composeTestRule
                    .onNodeWithTag(CHAT_HUB_TEST_TAG)
                    .getUnclippedBoundsInRoot()
                    .top
                    .toPx()
            }
        val mapTopPx =
            with(composeTestRule.density) {
                composeTestRule
                    .onNodeWithTag(MAP_HOME_TEST_TAG)
                    .getUnclippedBoundsInRoot()
                    .top
                    .toPx()
            }
        // Guard the premise: if the card ever covered the map's top there would be no
        // strip, and the tap below would land on the card and silently not dismiss.
        assertTrue(
            "expected an uncovered map strip above the card (map top $mapTopPx, " +
                "card top $cardTopPx)",
            cardTopPx > mapTopPx,
        )
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).performTouchInput {
            // Node-relative: the midpoint of the strip, measured down from the map's
            // own top edge.
            click(Offset(width / 2f, (cardTopPx - mapTopPx) / 2f))
        }
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertDoesNotExist()

        // Now the tabs are reachable again: leaving the Map tab takes the map home
        // away rather than floating it under another tab.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabHistory)).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertDoesNotExist()

        // Returning to the Map tab restores the map WITHOUT re-opening the hub —
        // the user gets the map, not a chat hub they never re-opened.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_tabMap)).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(CHAT_HUB_TEST_TAG).assertDoesNotExist()
    }
}
