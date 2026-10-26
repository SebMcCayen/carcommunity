package com.kungsbackacarcommunity.app.moderation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class MessageActionsSheetTest {
    @get:Rule val compose = createComposeRule()
    private data class Policy(val reply: Boolean, val block: Boolean, val report: Boolean, val scale: Float)

    @Test fun policyMatrixPreservesActionsCallbacksOrderAndTouchGeometry() {
        val policy = mutableStateOf(Policy(true, true, true, 1f))
        val calls = mutableListOf<String>()
        compose.setContent {
            val p = policy.value
            CompositionLocalProvider(LocalDensity provides Density(1f, p.scale)) {
                KccTheme {
                    Column(Modifier.requiredSize(320.dp, 400.dp).verticalScroll(rememberScrollState())) {
                        MessageActionsContent("Local member", p.block,
                            if (p.report) ReportAvailability.Wired else ReportAvailability.BackendMissing,
                            onBlock = { calls += "block" }, onReport = { calls += "report" },
                            onDismiss = { calls += "close" }, canReply = p.reply, onReply = { calls += "reply" })
                    }
                }
            }
        }
        for (scale in listOf(1f, 1.5f)) for (reply in listOf(false, true)) {
            for (report in listOf(false, true)) for (block in listOf(false, true)) {
                val p = Policy(reply, block, report, scale)
                compose.runOnIdle { calls.clear(); policy.value = p }
                val actions = listOf(
                    Triple(MESSAGE_ACTIONS_REPLY_TEST_TAG, reply, "reply"),
                    Triple(MESSAGE_ACTIONS_REPORT_TEST_TAG, report, "report"),
                    Triple(MESSAGE_ACTIONS_BLOCK_TEST_TAG, block, "block"),
                )
                var previousBottom = -1f
                for ((tag, visible, _) in actions) {
                    val action = compose.onNodeWithTag(tag)
                    if (!visible) action.assertDoesNotExist()
                    else {
                        action.performScrollTo().assertIsDisplayed()
                        val bounds = action.fetchSemanticsNode().boundsInRoot
                        assertTrue("$p $tag hit target: $bounds", bounds.height >= 48f && bounds.width >= 48f)
                        assertTrue("$p order: $tag", bounds.top >= previousBottom)
                        previousBottom = bounds.bottom
                        action.performClick()
                    }
                }
                compose.onNodeWithText(str(R.string.moderation_close)).performScrollTo().performClick()
                compose.runOnIdle {
                    assertEquals(actions.filter { it.second }.map { it.third } + "close", calls)
                }
            }
        }
    }

    @Test fun blockStillRequiresTheCallersConfirmation() {
        val confirming = mutableStateOf(false)
        var blocked = 0
        compose.setContent {
            KccTheme {
                MessageActionsContent(null, true, ReportAvailability.BackendMissing,
                    onBlock = { confirming.value = true }, onReport = {}, onDismiss = {})
                if (confirming.value) BlockConfirmDialog(null,
                    onConfirm = { blocked++; confirming.value = false }, onDismiss = { confirming.value = false })
            }
        }
        compose.onNodeWithTag(MESSAGE_ACTIONS_BLOCK_TEST_TAG).performClick()
        compose.runOnIdle { assertEquals(0, blocked) }
        compose.onNodeWithText(str(R.string.blocking_blockCancelAction)).performClick()
        compose.runOnIdle { assertEquals(0, blocked) }
        compose.onNodeWithTag(MESSAGE_ACTIONS_BLOCK_TEST_TAG).performClick()
        compose.onNodeWithText(str(R.string.blocking_blockConfirmAction)).performClick()
        compose.runOnIdle { assertEquals(1, blocked) }
    }

    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
