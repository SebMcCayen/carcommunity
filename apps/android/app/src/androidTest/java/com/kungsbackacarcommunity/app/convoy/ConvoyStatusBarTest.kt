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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
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
    private fun string(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

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

        fun exactErrorPixels(): Int {
            val pixels =
                composeTestRule
                    .onNodeWithTag(CONVOY_BAR_LEAVE_TAG)
                    .captureToImage()
                    .toPixelMap()
            var count = 0
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    if (pixels[x, y] == errorColor) count++
                }
            }
            return count
        }

        // Disabled (member: no `convoy.leave` callable) — no full-strength error pixels.
        assertEquals(
            "a disabled leave icon must not render in undimmed destructive red",
            0,
            exactErrorPixels(),
        )

        // Enabled (owner: `convoy-end` is wired) — the destructive colour IS used,
        // so the assertion above is about the disabled state and not about the
        // colour having been removed altogether.
        current = ownerState("c1")
        composeTestRule.waitForIdle()
        assertTrue(
            "an enabled end-convoy icon should still read as destructive",
            exactErrorPixels() > 0,
        )
    }
}
