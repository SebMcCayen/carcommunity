package com.kungsbackacarcommunity.app.convoy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.map.ConvoyFocusMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the convoy status bar's destructive control: WHICH convoy
 * a confirmed end actually ends, and whether the control's destructive colour
 * respects its own disabled state.
 */
@RunWith(AndroidJUnit4::class)
class ConvoyStatusBarTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Strings come from the resources rather than being retyped in English: the
     * app's DEFAULT resources are Swedish (`values/`), with English in
     * `values-en/`, so a hard-coded English literal would only match on an
     * English-locale device.
     */
    private fun string(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun ownerState(convoyId: String) =
        ConvoyBarState(
            convoyId = convoyId,
            memberCount = 2,
            viewerIsOwner = true,
            busy = false,
            inviteAvailability = ConvoyBar.inviteAvailability,
            leaveAvailability = ConvoyBar.leaveAvailability(viewerIsOwner = true),
        )

    private fun memberState(convoyId: String) =
        ConvoyBarState(
            convoyId = convoyId,
            memberCount = 2,
            viewerIsOwner = false,
            busy = false,
            inviteAvailability = ConvoyBar.inviteAvailability,
            leaveAvailability = ConvoyBar.leaveAvailability(viewerIsOwner = false),
        )

    /**
     * The one that matters: the bar's [ConvoyBarState] is hoisted and refreshes
     * underneath the composable, so the convoy it describes can change WHILE the
     * end-convoy confirmation is open. Confirming must end the convoy the dialog
     * was opened for, never whichever convoy the bar happens to be showing by the
     * time the user's finger lands.
     *
     * The assertion is on the OBSERVABLE — the convoy id `onEndConvoy` actually
     * received — not on the dialog closing, which would pass for any
     * implementation. Against a bar that reads `state.convoyId` inside the
     * confirm handler this fails with "expected:<c1> but was:<c2>": it really
     * ends the wrong convoy.
     */
    @Test
    fun confirmingAfterTheBarSwitchesConvoy_endsTheConvoyTheDialogWasOpenedFor() {
        var ended: String? = null
        var current by mutableStateOf(ownerState("c1"))
        composeTestRule.setContent {
            KccTheme { ConvoyStatusBar(state = current, onEndConvoy = { ended = it }) }
        }

        // Open the confirmation while the bar is describing convoy "c1".
        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()
        assertNull("nothing may be ended before the user confirms", ended)

        // A refresh swaps the bar over to a different convoy under the open
        // dialog — a newly started convoy outranking the forming one, say.
        current = ownerState("c2")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.convoy_barEndConfirmAction)).performClick()
        composeTestRule.waitForIdle()

        assertEquals("must end the convoy the dialog named, not the bar's new one", "c1", ended)
    }

    /**
     * The convoy the dialog is about is fixed at open time, so a refresh must not
     * silently cancel a considered destructive decision (which is what keying the
     * dialog flag to `state.convoyId` would do). The dialog stays up.
     */
    @Test
    fun switchingConvoyUnderTheOpenDialog_doesNotSilentlyDismissIt() {
        var current by mutableStateOf(ownerState("c1"))
        composeTestRule.setContent {
            KccTheme { ConvoyStatusBar(state = current, onEndConvoy = {}) }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()

        current = ownerState("c2")
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText(string(R.string.convoy_barEndConfirmBody))
            .assertIsDisplayed()
    }

    /**
     * The one failure in this component that would actually hurt people: a
     * member tapping "leave" and silently ending everyone's drive. The trailing
     * control routes on `viewerIsOwner`, not on `leaveAvailability`, so this
     * holds even in the future state where `convoy.leave` has landed and the
     * availability flag has been flipped for members — the case a click path
     * keyed on availability alone would get wrong.
     */
    @Test
    fun aMembersLeaveNeverReachesTheEndConvoyPath_evenOnceLeaveIsWired() {
        var ended: String? = null
        var left: String? = null
        // The post-`convoy.leave` world: member, leave wired, handler supplied.
        val state =
            memberState("c1").copy(leaveAvailability = ConvoyBarActionAvailability.Wired)
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = state,
                    onEndConvoy = { ended = it },
                    onLeaveConvoy = { left = it },
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()

        // No confirmation dialog: that belongs to "end", which is not this action.
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barEndConfirmBody))
            .assertDoesNotExist()
        assertEquals("a member's leave must reach the leave handler", "c1", left)
        assertNull("a member's leave must never end the convoy for everyone", ended)
    }

    /**
     * The invite control's enablement is derived, not hard-coded: flipping
     * `inviteAvailability` to `Wired` when the `convoy.invite` callable ships
     * must actually make the button live, and must do so only alongside a
     * handler. Asserted through the observable — whether a tap reaches
     * `onInvite` — rather than by reading the `enabled` argument back.
     */
    @Test
    fun inviteControl_goesLiveOnlyWithBothAWiredFlagAndAHandler() {
        var invited: String? = null
        val wired = ConvoyBarActionAvailability.Wired
        var current by
            mutableStateOf(ownerState("c1").copy(inviteAvailability = wired))
        var handler: ((String) -> Unit)? by mutableStateOf(null)
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(state = current, onEndConvoy = {}, onInvite = handler)
            }
        }

        // Wired flag, but no handler → still inert.
        composeTestRule.onNodeWithTag(CONVOY_BAR_INVITE_TAG).performClick()
        composeTestRule.waitForIdle()
        assertNull("a wired flag alone must not make the control act", invited)

        // Handler, but the flag back to today's BackendMissing → still inert.
        handler = { invited = it }
        current = ownerState("c1")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CONVOY_BAR_INVITE_TAG).performClick()
        composeTestRule.waitForIdle()
        assertNull("a handler alone must not make the control act", invited)

        // Both → live, and it invites THIS convoy.
        current = ownerState("c1").copy(inviteAvailability = wired)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CONVOY_BAR_INVITE_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals("c1", invited)
    }

    /**
     * A flag flipped ahead of its handler leaves the controls disabled — so the
     * accessibility labels must keep saying the actions are unavailable, because
     * they still are. A disabled button that announces itself as "Invite more" /
     * "Leave convoy" tells a screen-reader user, who has no visual disabled cue to
     * fall back on, that something is available when it does nothing.
     *
     * The unavailability is carried ONLY by the controls' "…unavailable"
     * contentDescriptions: there is no visible "not available yet" body-text
     * notice line (it was removed as map clutter), so this also asserts the notice
     * string is NOT rendered.
     */
    @Test
    fun aWiredFlagWithoutItsHandler_stillAnnouncesAsUnavailableWithNoVisibleNotice() {
        val wired = ConvoyBarActionAvailability.Wired
        // Both flags flipped, neither handler supplied — the mid-refactor state.
        val state =
            memberState("c1").copy(inviteAvailability = wired, leaveAvailability = wired)
        composeTestRule.setContent {
            KccTheme { ConvoyStatusBar(state = state, onEndConvoy = {}) }
        }

        composeTestRule
            .onNodeWithContentDescription(string(R.string.convoy_barInviteUnavailable))
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.convoy_barLeaveUnavailable))
            .assertExists()
        // The visible explanation line is gone — accessibility rides on the
        // contentDescriptions above, not a separate paragraph.
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barNoticeInviteAndLeave))
            .assertDoesNotExist()
    }

    /**
     * A member's leave control has no callable and is disabled — and must not
     * still be painted in full-strength destructive red, which reads as tappable.
     *
     * Asserted on pixels rather than on the presence of a `tint` argument: the
     * icon is measured for pixels of EXACTLY the theme's error colour, which a
     * hard-coded `tint = colorScheme.error` produces (it bypasses the
     * IconButton's disabled content colour entirely) and a properly disabled
     * control does not. The enabled owner case is asserted in the same units, so
     * the test also fails if the destructive colour is simply dropped.
     */
    @Test
    fun disabledLeaveIcon_isNotPaintedFullStrengthDestructiveRed() {
        var errorColor = Color.Unspecified
        var current by mutableStateOf(memberState("c1"))
        composeTestRule.setContent {
            KccTheme {
                errorColor = MaterialTheme.colorScheme.error
                ConvoyStatusBar(state = current, onEndConvoy = {})
            }
        }
        composeTestRule.waitForIdle()

        // Matched with a small per-channel tolerance rather than by exact Color
        // equality: the icon is a tinted vector, so its interior really is the
        // flat tint, but 8-bit quantisation and renderer differences across
        // devices can shift a channel by a step or two. The tolerance is far
        // below the effect being measured — a properly disabled icon is the error
        // colour at ~38% opacity composited over the surface, tens of steps away
        // per channel, not one or two — so it removes the flake without blunting
        // the assertion.
        fun undimmedErrorPixels(): Int {
            val pixels =
                composeTestRule
                    .onNodeWithTag(CONVOY_BAR_LEAVE_TAG)
                    .captureToImage()
                    .toPixelMap()
            val tolerance = 4f / 255f
            fun near(a: Float, b: Float) = kotlin.math.abs(a - b) <= tolerance
            var count = 0
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val p = pixels[x, y]
                    if (near(p.red, errorColor.red) &&
                        near(p.green, errorColor.green) &&
                        near(p.blue, errorColor.blue) &&
                        near(p.alpha, errorColor.alpha)
                    ) {
                        count++
                    }
                }
            }
            return count
        }

        // Disabled (member: no `convoy.leave` callable) — no full-strength error pixels.
        assertEquals(
            "a disabled leave icon must not render in undimmed destructive red",
            0,
            undimmedErrorPixels(),
        )

        // Enabled (owner: `convoy-end` is wired) — the destructive colour IS used,
        // so the assertion above is about the disabled state and not about the
        // colour having been removed altogether.
        current = ownerState("c1")
        composeTestRule.waitForIdle()
        assertTrue(
            "an enabled end-convoy icon should still read as destructive",
            undimmedErrorPixels() > 0,
        )
    }

    // --- compact inline pill (the map's search-row placement) ---------------

    /**
     * The complaint behind the relocation: sitting in a convoy, "I can't press any
     * buttons except the location button". The bar's one always-operable control
     * is the map-focus toggle (the crosshair a user reads as "location"); every
     * other action is disabled until its backend callable ships. This asserts the
     * focus toggle is genuinely clickable in the compact pill's new home INSIDE the
     * search row — its callback fires on a tap — rather than merely rendered.
     */
    @Test
    fun inlinePill_focusToggle_isClickable_andFiresItsCallback() {
        var picked: ConvoyFocusMode? = null
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBarInline(
                    memberCount = 3,
                    focusMode = ConvoyFocusMode.Me,
                    onFocusModeChange = { picked = it },
                    expandedContent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_INLINE_FOCUS_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            "tapping the inline focus toggle must reach its callback",
            ConvoyFocusMode.Convoy,
            picked,
        )
    }

    /**
     * The rest of the convoy actions live behind the pill's expand control, in the
     * full [ConvoyStatusBar] rendered in a popup. This proves that path end-to-end:
     * the full bar is not composed until expanded, and once it is, its owner End
     * control is reachable and actually drives the confirm → callback. A popup that
     * swallowed touches (the failure the relocation guards against) would fail here.
     */
    @Test
    fun inlinePill_expand_opensTheFullBar_whoseEndControlActuallyFires() {
        var ended: String? = null
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBarInline(
                    memberCount = 2,
                    expandedContent = {
                        ConvoyStatusBar(state = ownerState("c1"), onEndConvoy = { ended = it })
                    },
                )
            }
        }

        // Collapsed: the full bar (and its controls) are not in the tree at all.
        composeTestRule.onNodeWithTag(CONVOY_BAR_TEST_TAG).assertDoesNotExist()

        composeTestRule.onNodeWithTag(CONVOY_BAR_EXPAND_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CONVOY_BAR_EXPAND_POPUP_TAG).assertExists()

        // The End control inside the popup receives the tap and runs the confirm.
        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barEndConfirmAction))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals("the expanded bar's End control must reach its callback", "c1", ended)
    }

    /**
     * Icons and numbers only: the pill shows the bare member count, and the count
     * `Text` node carries the full "N in the convoy" contentDescription (the group
     * glyph is decorative, `contentDescription = null`) so TalkBack announces the
     * count in context rather than reading a context-free "3".
     */
    @Test
    fun inlinePill_showsBareCount_withTheFullAnnouncementForTalkBack() {
        composeTestRule.setContent {
            KccTheme { ConvoyStatusBarInline(memberCount = 4, expandedContent = {}) }
        }

        composeTestRule.onNodeWithText("4").assertExists()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.convoy_barMembers, 4))
            .assertExists()
    }
}
