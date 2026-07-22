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

    private val twoMembers =
        listOf(
            ConvoyBarMember(uid = "u1", displayName = "Alice", avatarPath = null),
            ConvoyBarMember(uid = "u2", displayName = "Bob", avatarPath = null),
        )

    private fun ownerState(convoyId: String) =
        ConvoyBarState(
            convoyId = convoyId,
            members = twoMembers,
            viewerIsOwner = true,
            busy = false,
            inviteAvailability = ConvoyBar.inviteAvailability,
            leaveAvailability = ConvoyBar.leaveAvailability,
        )

    private fun memberState(convoyId: String) =
        ConvoyBarState(
            convoyId = convoyId,
            members = twoMembers,
            viewerIsOwner = false,
            busy = false,
            inviteAvailability = ConvoyBar.inviteAvailability,
            leaveAvailability = ConvoyBar.leaveAvailability,
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
     * control routes on `viewerIsOwner`, not on `leaveAvailability`, so a member's
     * tap opens the LEAVE confirmation (never END's) and, once confirmed, reaches
     * the leave handler alone — the case a click path keyed on availability would
     * get wrong now that `convoy-leave` is wired and the member flag is Wired.
     */
    @Test
    fun aMembersLeaveNeverReachesTheEndConvoyPath() {
        var ended: String? = null
        var left: String? = null
        // Member, leave wired (as it now is), handler supplied.
        val state = memberState("c1")
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

        // The confirmation shown is LEAVE's, never END's (which ends the convoy
        // for everyone); nothing has left yet until the user confirms.
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barEndConfirmBody))
            .assertDoesNotExist()
        assertNull("leaving must wait for the confirmation", left)

        composeTestRule
            .onNodeWithText(string(R.string.convoy_barLeaveConfirmAction))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals("a member's leave must reach the leave handler", "c1", left)
        assertNull("a member's leave must never end the convoy for everyone", ended)
    }

    /**
     * The invite control's enablement is DERIVED from both halves, not hard-coded:
     * the `Wired` availability flag AND a supplied handler. Neither alone makes the
     * button act. Asserted through the observable — whether a tap reaches
     * `onInvite` — rather than by reading the `enabled` argument back.
     */
    @Test
    fun inviteControl_goesLiveOnlyWithBothAWiredFlagAndAHandler() {
        var invited: String? = null
        val wired = ConvoyBarActionAvailability.Wired
        val missing = ConvoyBarActionAvailability.BackendMissing
        // Wired flag (the default now), but no handler → inert.
        var current by mutableStateOf(ownerState("c1").copy(inviteAvailability = wired))
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

        // Handler, but the flag forced back to BackendMissing → still inert.
        handler = { invited = it }
        current = ownerState("c1").copy(inviteAvailability = missing)
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

    // --- all controls inline (no expand, no popup) --------------------------

    /**
     * The relocation's whole point: every convoy control is now inline in the one
     * always-visible bar — no expand step, no popup. The map-focus toggle, the
     * invite control and the leave/End control are all in the tree at once and
     * reachable directly. (The individual actions' behaviour is asserted in the
     * focused tests above; this one pins that they are all inline together.)
     */
    @Test
    fun allControlsRenderInlineWithoutAnExpandStep() {
        var picked: ConvoyFocusMode? = null
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1"),
                    onEndConvoy = {},
                    onInvite = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                    focusMode = ConvoyFocusMode.Me,
                    onFocusModeChange = { picked = it },
                )
            }
        }

        // All three controls are present inline, no expand required.
        composeTestRule.onNodeWithTag(CONVOY_BAR_FOCUS_TAG).assertExists()
        composeTestRule.onNodeWithTag(CONVOY_BAR_INVITE_TAG).assertExists()
        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).assertExists()

        // And the focus toggle is genuinely operable inline (the "I can only press
        // the location button" complaint) — its callback fires on a tap.
        composeTestRule.onNodeWithTag(CONVOY_BAR_FOCUS_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals(ConvoyFocusMode.Convoy, picked)
    }

    /**
     * The member count starts as a tappable control that opens a member-list
     * popup (previously nothing happened on tap). Tapping it must reveal the
     * popup listing every accepted member by name — here both `twoMembers`.
     */
    @Test
    fun tappingMemberCount_opensMemberListWithEveryMember() {
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1"),
                    onEndConvoy = {},
                    onInvite = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                )
            }
        }

        // Closed to start: no member list on screen.
        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_LIST_TAG).assertDoesNotExist()

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBERS_TAG).performClick()
        composeTestRule.waitForIdle()

        // The popup is up and lists both members by display name.
        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_LIST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
    }

    /**
     * The member-list popup is a passive info surface, so — unlike the destructive
     * end/leave dialogs — it must NOT linger across a convoy switch showing the new
     * convoy's roster under the count the user tapped. Its visibility flag is keyed
     * to the convoy id, so a background refresh that swaps the bar to a different
     * convoy closes the popup.
     */
    @Test
    fun switchingConvoyUnderTheOpenMemberList_closesIt() {
        var current by mutableStateOf(memberState("c1"))
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = current,
                    onEndConvoy = {},
                    onInvite = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBERS_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_LIST_TAG).assertIsDisplayed()

        // A refresh swaps the bar to a different convoy under the open popup.
        current = memberState("c2")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_LIST_TAG).assertDoesNotExist()
    }

    /**
     * The tappable count keeps the full "%d in convoy" phrase as its
     * contentDescription (accessibility) even though only the bare number is
     * shown, so TalkBack still announces the whole thing.
     */
    @Test
    fun memberCount_keepsFullPhraseAsContentDescription() {
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1"),
                    onEndConvoy = {},
                    onInvite = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(string(R.string.convoy_barMembers, twoMembers.size))
            .assertIsDisplayed()
    }
}
