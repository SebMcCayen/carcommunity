package com.kungsbackacarcommunity.app.notifications

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.friends.FriendActionError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ── Friend-request accept/decline in the inbox ──────────────────────────

    private val requesterUid = "uid-requester"
    private val requestId = "req-abc123"

    /** The row the backend writes for a NEW incoming friend request. */
    private fun friendRequestItem(
        actionType: NotificationActionType = NotificationActionType.OPEN_NOTIFICATIONS,
    ) = AppNotification(
        id = "fr1",
        category = NotificationCategory.FRIEND_REQUEST,
        title = "Ny vanforfragan",
        previewText = "Someone wants to be your friend",
        body = null,
        isRead = true,
        createdAtMillis = 0L,
        actionType = actionType,
        relatedEntityId = requesterUid,
    )

    @Test
    fun pendingFriendRequest_acceptPassesTheRequestId() {
        var accepted: String? = null
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(friendRequestItem())),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    pendingFriendRequestIds = mapOf(requesterUid to requestId),
                    onAcceptFriendRequest = { accepted = it },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.friends_accept)).performScrollTo().performClick()
        // The REQUEST id, not the notification id and not the requester uid:
        // friend-respondRequest takes the friendRequests document id, and the
        // other two would fail not-found for every request.
        assertEquals(requestId, accepted)
    }

    @Test
    fun pendingFriendRequest_declinePassesTheRequestId() {
        var declined: String? = null
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(friendRequestItem())),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    pendingFriendRequestIds = mapOf(requesterUid to requestId),
                    onDeclineFriendRequest = { declined = it },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.friends_decline)).performScrollTo().performClick()
        assertEquals(requestId, declined)
    }

    @Test
    fun alreadyAnsweredFriendRequest_showsNoActionsAtAll() {
        // Accepted/declined elsewhere (profile screen, another device): the
        // notification itself is unchanged — it is never rewritten — so the
        // ONLY signal is that it is gone from the pending list. The row must
        // not keep offering buttons that no longer do anything.
        var accepted: String? = null
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(friendRequestItem())),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    pendingFriendRequestIds = emptyMap(),
                    onAcceptFriendRequest = { accepted = it },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.friends_accept)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.friends_decline)).assertDoesNotExist()
        assertNull(accepted)
        // The row itself still renders — the news doesn't vanish, only the actions.
        composeTestRule.onNodeWithText("Ny vanforfragan").assertIsDisplayed()
    }

    @Test
    fun friendRequestAcceptedReceipt_showsNoActions() {
        // Same category, opposite meaning ("X accepted your request"), written
        // with open_profile. The pending map deliberately DOES contain this
        // member — the unfriend-then-re-request state — so only the actionType
        // gate keeps the old receipt from growing buttons.
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state =
                        NotificationsState.Loaded(
                            listOf(friendRequestItem(NotificationActionType.OPEN_PROFILE)),
                        ),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    pendingFriendRequestIds = mapOf(requesterUid to requestId),
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.friends_accept)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.friends_decline)).assertDoesNotExist()
    }

    @Test
    fun inFlightFriendRequest_disablesBothActions() {
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(friendRequestItem())),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    pendingFriendRequestIds = mapOf(requesterUid to requestId),
                    busyFriendRequestIds = setOf(requestId),
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.friends_accept)).performScrollTo()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText(str(R.string.friends_decline)).assertIsNotEnabled()
    }

    @Test
    fun failedResponse_surfacesTheServerAnswerAndIsDismissible() {
        // A stale accept comes back as RequestGone (friend-respondRequest maps
        // both not-found and failed-precondition there). It must read as an
        // honest sentence, never a raw error, and the row must NOT be left
        // claiming a friendship the server refused to create.
        var dismissed = 0
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(friendRequestItem())),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    pendingFriendRequestIds = emptyMap(),
                    friendActionError = FriendActionError.RequestGone,
                    onDismissFriendActionError = { dismissed++ },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.friends_errorRequestGone)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.notifications_errorDismiss))
            .performScrollTo()
            .performClick()
        assertEquals(1, dismissed)
    }

    // ── Deleting ───────────────────────────────────────────────────────────

    @Test
    fun deleteAll_isOfferedOnlyWhenThereIsSomethingToDelete() {
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
        composeTestRule.onNodeWithText(str(R.string.notifications_deleteAll)).assertDoesNotExist()
    }

    @Test
    fun deleteAll_asksBeforeDoingIt() {
        // Irreversible, so the small text must not be a one-tap sweep.
        var deletedAll = 0
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(item("n1", read = true))),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    onDeleteAll = { deletedAll++ },
                )
            }
        }
        composeTestRule
            .onNodeWithText(str(R.string.notifications_deleteAll))
            .performScrollTo()
            .performClick()
        // Nothing has happened yet — the dialog is the gate.
        assertEquals(0, deletedAll)
        composeTestRule
            .onNodeWithText(str(R.string.notifications_deleteAllConfirmTitle))
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText(str(R.string.notifications_deleteAllConfirmAction))
            .performClick()
        assertEquals(1, deletedAll)
    }

    @Test
    fun deleteAll_cancelLeavesEverythingAlone() {
        var deletedAll = 0
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(item("n1", read = true))),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    onDeleteAll = { deletedAll++ },
                )
            }
        }
        composeTestRule
            .onNodeWithText(str(R.string.notifications_deleteAll))
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNodeWithText(str(R.string.notifications_deleteAllCancel))
            .performClick()

        assertEquals(0, deletedAll)
        composeTestRule
            .onNodeWithText(str(R.string.notifications_deleteAllConfirmTitle))
            .assertDoesNotExist()
        // The row it would have deleted is still there.
        composeTestRule.onNodeWithText("Event soon").assertIsDisplayed()
    }

    @Test
    fun swipingARowRightToLeftDeletesIt() {
        var deleted: String? = null
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(item("n1", read = true))),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    onDeleteNotification = { deleted = it },
                )
            }
        }
        composeTestRule
            .onNodeWithTag(NOTIFICATION_ROW_TEST_TAG)
            .performScrollTo()
            .performTouchInput {
                // Across the ROW's full width — well past the half-width
                // threshold, so this is a committed swipe rather than a flick
                // that should spring back.
                swipeLeft(startX = right - 1f, endX = left + 1f)
            }
        composeTestRule.waitForIdle()
        assertEquals("n1", deleted)
    }

    @Test
    fun deleteIsReachableWithoutSwiping() {
        // A drag is invisible to a screen reader, so the same action is also
        // published as a semantics custom action on every row.
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(item("n1", read = true))),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    onDeleteNotification = {},
                )
            }
        }
        val label = str(R.string.notifications_deleteAction)
        composeTestRule
            .onNode(
                SemanticsMatcher("has a '$label' custom action") { node ->
                    node.config
                        .getOrNull(SemanticsActions.CustomActions)
                        ?.any { it.label == label } == true
                },
            )
            .assertExists()
    }

    @Test
    fun aFailedDeleteIsSurfacedAndTheRowIsStillThere() {
        // What must never happen is a notification quietly vanishing while it
        // still exists on the server.
        var dismissed = 0
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(item("n1", read = true))),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    deleteError = NotificationDeleteError.SINGLE,
                    onDismissDeleteError = { dismissed++ },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.notifications_deleteError)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Event soon").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.notifications_errorDismiss))
            .performScrollTo()
            .performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun aFailedDeleteAllSaysSoInItsOwnWords() {
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(item("n1", read = true))),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    deleteError = NotificationDeleteError.ALL,
                    onDismissDeleteError = {},
                )
            }
        }
        composeTestRule
            .onNodeWithText(str(R.string.notifications_deleteAllError))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.notifications_deleteError)).assertDoesNotExist()
    }

    @Test
    fun deletingTheLastRowLandsOnTheEmptyState() {
        // The caller filters deleted rows out of the state it passes down, so
        // an inbox emptied by deleting must read exactly like one that was
        // never filled — not a blank page.
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(emptyList()),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                    onDeleteNotification = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.notifications_empty)).assertIsDisplayed()
    }

    @Test
    fun noFriendState_leavesTheInboxExactlyAsItWas() {
        // The config-less build passes no friend data at all.
        composeTestRule.setContent {
            KccTheme {
                NotificationsScreen(
                    state = NotificationsState.Loaded(listOf(friendRequestItem())),
                    onMarkRead = {},
                    onMarkAllRead = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.friends_accept)).assertDoesNotExist()
        composeTestRule.onNodeWithText("Ny vanforfragan").assertIsDisplayed()
    }
}
