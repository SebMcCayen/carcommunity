package com.kungsbackacarcommunity.app.convoy

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Removing labels must preserve spoken actions, cooldowns and usable targets. */
@RunWith(AndroidJUnit4::class)
class ConvoyReactionAppearanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun iconOnlyTargetsFitNarrowScreensAtLargeFontScales() {
        val width = mutableStateOf(280)
        val scale = mutableStateOf(1f)
        val dark = mutableStateOf(true)
        val sent = mutableListOf<ConvoyReactionKind>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
                KccTheme(darkTheme = dark.value) {
                    Box(Modifier.requiredWidth(width.value.dp)) {
                        ConvoyReactionButtons(ConvoyReactionCooldownState(), 0L, { sent += it })
                    }
                }
            }
        }
        for (w in listOf(280, 390)) for (font in listOf(1f, 2f)) for (night in listOf(true, false)) {
            rule.runOnIdle { width.value = w; scale.value = font; dark.value = night }
            val row = rule.onNodeWithTag(CONVOY_REACTIONS_TAG).fetchSemanticsNode().boundsInRoot
            val targets = ConvoyReactionKind.entries.map { kind ->
                val node = rule.onNodeWithTag(convoyReactionButtonTag(kind)).assertIsEnabled()
                val config = node.fetchSemanticsNode().config
                assertTrue("$kind keeps a spoken name", config[SemanticsProperties.ContentDescription].first().isNotBlank())
                node.fetchSemanticsNode().boundsInRoot.also {
                    assertTrue("$kind has a rectangular 48dp minimum target", it.width > it.height && it.height >= 48f)
                    assertTrue("$kind fits screen", it.left >= row.left && it.right <= row.right)
                }
            }
            for (index in 0 until targets.lastIndex) assertTrue(targets[index].right < targets[index + 1].left)
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                .assertCountEquals(0)
        }
        ConvoyReactionKind.entries.forEach { rule.onNodeWithTag(convoyReactionButtonTag(it)).performClick() }
        rule.runOnIdle { assertEquals(ConvoyReactionKind.entries.toList(), sent) }
    }

    @Test fun cooldownAndActiveFollowMeRemainAccessibleWithoutVisibleCountdowns() {
        val now = 1_000L
        val cooldown = ConvoyReactionCooldownState().recordSent(ConvoyReactionKind.Police, now)
        rule.setContent {
            KccTheme {
                ConvoyReactionButtons(cooldown, now + 10_000L, {}, followMeLeading = true, followMeToggleEnabled = true)
            }
        }
        rule.onNodeWithTag(convoyReactionButtonTag(ConvoyReactionKind.Police)).assertIsNotEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                rule.activity.getString(R.string.convoyReaction_cooldown, 50)),
        )
        rule.onNodeWithTag(convoyReactionButtonTag(ConvoyReactionKind.HelloGoodbye)).assertIsEnabled()
        rule.onNodeWithTag(convoyReactionButtonTag(ConvoyReactionKind.FollowMe)).assertIsEnabled().assertIsSelected()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .assertCountEquals(0)
    }
}
