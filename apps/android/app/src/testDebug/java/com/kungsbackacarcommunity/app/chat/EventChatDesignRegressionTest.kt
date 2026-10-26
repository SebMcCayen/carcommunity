package com.kungsbackacarcommunity.app.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventChatDesignRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val messages = ChatMessagesState.Loaded(listOf(
        ChatMessage("test-message", "other", "Test author", "Private test body", false, false, 0L),
    ))

    @Test
    fun actualEventTitleAndBackActionDoNotDependOnMessageHistory() {
        var backs = 0
        compose.setContent {
            KccTheme {
                EventChatScreen(
                    state = ChatMessagesState.Loaded(emptyList()), currentUid = "me", canParticipate = true,
                    sendStatus = ChatSendStatus.Idle, reportStatus = ChatReportStatus.Idle,
                    onSend = {}, onReport = { _, _ -> }, onReportDismiss = {}, onBack = { backs++ },
                    eventTitle = "Actual test event",
                )
            }
        }
        compose.onNodeWithText("Actual test event").assertIsDisplayed()
        val back = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.profile_back)
        compose.onNodeWithContentDescription(back).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun lostParticipationHidesLoadedBodiesAuthorsAndComposer() {
        compose.setContent {
            KccTheme {
                EventChatScreen(
                    state = messages, currentUid = "me", canParticipate = false,
                    sendStatus = ChatSendStatus.Idle, reportStatus = ChatReportStatus.Idle,
                    onSend = {}, onReport = { _, _ -> }, onReportDismiss = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithText("Private test body").assertDoesNotExist()
        compose.onNodeWithText("Test author").assertDoesNotExist()
        val placeholder = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_inputPlaceholder)
        compose.onNodeWithText(placeholder).assertDoesNotExist()
    }
}
