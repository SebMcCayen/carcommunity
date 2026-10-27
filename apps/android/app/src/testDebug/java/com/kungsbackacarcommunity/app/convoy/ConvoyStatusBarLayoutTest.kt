package com.kungsbackacarcommunity.app.convoy

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w450dp-h900dp-mdpi")
class ConvoyStatusBarLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private data class Variant(val width: Int, val scale: Float, val compact: Boolean)

    @Test fun everyControlFitsWithoutOverlapAtNarrowWidthsAndLargeText() {
        val variant = mutableStateOf(Variant(280, 1f, false))
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, variant.value.scale)) {
                KccTheme {
                    Box(Modifier.requiredWidth(variant.value.width.dp)) {
                        ConvoyStatusBar(
                            state = ConvoyBarState(convoyId = "local", members = List(12) { ConvoyBarMember("u$it", "Local $it", null) },
                                viewerIsOwner = true, busy = false, inviteAvailability = ConvoyBar.inviteAvailability,
                                leaveAvailability = ConvoyBar.leaveAvailability,
                                unreadChatCount = 250),
                            onEndConvoy = {}, onLeaveConvoy = {}, onInvite = {}, onOpenChat = {},
                            compact = variant.value.compact, showDestination = false,
                        )
                    }
                }
            }
        }
        for (width in listOf(280, 350)) for (scale in listOf(1f, 2f)) for (compact in listOf(false, true)) {
            val current = Variant(width, scale, compact)
            rule.runOnIdle { variant.value = current }
            rule.waitForIdle()
            val bar = rule.onNodeWithTag(CONVOY_BAR_TEST_TAG).fetchSemanticsNode().boundsInRoot
            val tags = listOf(CONVOY_BAR_MEMBERS_TAG, CONVOY_BAR_CHAT_TAG, CONVOY_BAR_INVITE_TAG,
                CONVOY_BAR_LEAVE_TAG) + if (compact) emptyList() else listOf(CONVOY_BAR_FOCUS_TAG)
            val controls = tags.map { tag ->
                rule.onNodeWithTag(tag).assertIsDisplayed()
                rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.also {
                    assertTrue("$current $tag 48dp target: $it in $bar", it.width >= 48f && it.height >= 48f)
                    assertTrue("$current $tag inside bar", it.left >= bar.left && it.right <= bar.right &&
                        it.top >= bar.top && it.bottom <= bar.bottom)
                }
            }
            for (a in controls.indices) for (b in a + 1 until controls.size) {
                assertTrue("$current no overlapping targets", !controls[a].overlaps(controls[b]))
            }
        }
    }
}
