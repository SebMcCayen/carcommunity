package com.kungsbackacarcommunity.app.convoy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.testutil.RetryRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one convoy-status-bar assertion that must stay on the emulator: it reads back
 * rendered PIXELS (captureToImage / toPixelMap). Robolectric's captureToImage path
 * hangs in forceRedraw (there is no real window draw callback off-device), so this
 * cannot move to the fast JVM Robolectric suite the way the rest of
 * ConvoyStatusBarTest did (issue #759 Batch 3). Same GPU/rendering reason
 * ConvoyMapAwarenessOverlayTest stays on-device.
 */
@RunWith(RetryRunner::class)
class ConvoyStatusBarDestructiveColorTest {
    @get:Rule
    val composeTestRule = createComposeRule()

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
     * An exit control with no handler wired is disabled — and must not still be
     * painted in full-strength destructive red, which reads as tappable.
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

        // Disabled (a member whose host wired no leave handler) — no
        // full-strength error pixels.
        assertEquals(
            "a disabled leave icon must not render in undimmed destructive red",
            0,
            undimmedErrorPixels(),
        )

        // Enabled (the leader's End is wired by signature) — the destructive
        // colour IS used,
        // so the assertion above is about the disabled state and not about the
        // colour having been removed altogether.
        current = ownerState("c1")
        composeTestRule.waitForIdle()
        assertTrue(
            "an enabled end-convoy icon should still read as destructive",
            undimmedErrorPixels() > 0,
        )
    }
}
