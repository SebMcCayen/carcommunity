package com.kungsbackacarcommunity.app.notifications

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the notification inbox (Phase 12 slice 21).
 */
@RunWith(AndroidJUnit4::class)
class NotificationsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun item(id: String, read: Boolean) =
        AppNotification(
            id = id,
            category = NotificationCategory.EVENT_REMINDER,
            title = "Event soon",
            previewText = "Don't miss it",
            body = null,
            isRead = read,
            createdAtMillis = 0L,
        )

    @Test
    fun empty_showsEmptyState() {
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(emptyList()),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.notifications_empty)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.notifications_markAllRead)).assertDoesNotExist()
    }

    @Test
    fun unread_showsMarkAllRead_andTapMarksRead() {
        var markedRead: String? = null
        var markedAll = 0
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(item("n1", read = false))),
                    onMarkRead = { markedRead = it },
                    onMarkAllRead = { markedAll++ },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.notifications_markAllRead)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.notifications_unreadLabel)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Event soon").performScrollTo().performClick()
        assertEquals("n1", markedRead)
    }

    @Test
    fun allRead_hidesMarkAllRead() {
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(item("n1", read = true))),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.notifications_markAllRead)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.notifications_unreadLabel)).assertDoesNotExist()
    }
}
