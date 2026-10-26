package com.kungsbackacarcommunity.app.dm

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.moderation.MESSAGE_ACTIONS_BLOCK_TEST_TAG
import com.kungsbackacarcommunity.app.moderation.MESSAGE_ACTIONS_PREVIEW_TEST_TAG
import com.kungsbackacarcommunity.app.moderation.MESSAGE_ACTIONS_REPORT_TEST_TAG
import com.kungsbackacarcommunity.app.moderation.MESSAGE_ACTIONS_SHEET_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMessageActionsPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ownQuoteUsesYouAndIncomingQuoteUsesCounterpartyWithoutChangingConfirmation() {
        val messages = mutableStateOf(listOf(DmMessage("own", "me", "Local outgoing body", 0L, null)))
        var blocked: String? = null
        compose.setContent {
            KccTheme {
                ChatScreen(otherName = "Local counterparty", messages = messages.value, currentUid = "me",
                    threadLoading = false, canLoadOlder = false, isLoadingOlder = false,
                    onSend = { _, _ -> }, onRetry = {}, onLoadOlder = {}, otherUid = "other",
                    onBlock = { blocked = it }, chatRepliesEnabled = true)
            }
        }
        fun quote(text: String) = compose.onNode(
            hasText(text) and hasAnyAncestor(hasTestTag(MESSAGE_ACTIONS_PREVIEW_TEST_TAG)),
            useUnmergedTree = true)
        compose.onNodeWithText("Local outgoing body").performTouchInput { longClick() }
        quote(str(R.string.shell_userMarkerLabel)).assertIsDisplayed()
        quote("Local outgoing body").assertIsDisplayed()
        quote("Local counterparty").assertDoesNotExist()
        compose.onNodeWithTag(MESSAGE_ACTIONS_BLOCK_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MESSAGE_ACTIONS_REPORT_TEST_TAG).assertDoesNotExist()

        compose.runOnIdle { messages.value = listOf(DmMessage("incoming", "other", "Local incoming body", 60_000L, null)) }
        compose.onNodeWithTag(MESSAGE_ACTIONS_PREVIEW_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MESSAGE_ACTIONS_SHEET_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithText("Local incoming body").performTouchInput { longClick() }
        quote("Local counterparty").assertIsDisplayed()
        quote("Local incoming body").assertIsDisplayed()
        compose.onNodeWithTag(MESSAGE_ACTIONS_BLOCK_TEST_TAG).performClick()
        compose.runOnIdle { assertNull(blocked) }
        compose.onNodeWithText(str(R.string.blocking_blockConfirmAction)).performClick()
        compose.runOnIdle { assertEquals("other", blocked) }
    }

    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
