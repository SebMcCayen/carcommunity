package com.kungsbackacarcommunity.app.events

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.design.LocalSnackbarHostState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventCreateNavigationTest {
    @get:Rule val compose = createComposeRule()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun confirmedCreateReturnsToListWhileSuccessSnackbarIsStillPending() {
        val repository = PendingCreateRepository()
        // Deliberately no SnackbarHost: nothing dismisses this message. A route
        // that awaits showSnackbar before returning will remain on the form.
        val snackbar = SnackbarHostState()
        compose.setContent {
            KccTheme {
                CompositionLocalProvider(LocalSnackbarHostState provides snackbar) {
                    EventsRoute(
                        repository = repository, rsvpCoordinator = null,
                        uid = "test-creator", passesMemberGate = true,
                        chatRepository = null, chatCoordinator = null, chatEnabled = false,
                        groupDriveRepository = null, groupDriveCoordinator = null,
                        onBack = {}, errorReporter = null,
                    )
                }
            }
        }

        compose.onNodeWithText(str(R.string.events_createButton)).performClick()
        compose.onNodeWithTag(EVENT_EDITOR_TITLE_TAG).performTextInput("Local test meet")
        compose.onNodeWithTag(EVENT_EDITOR_START_TAG).performScrollTo().performClick()
        compose.onNode(hasText("15", substring = true) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(str(R.string.events_createDialogNext)).performClick()
        compose.onNodeWithText(str(R.string.events_createDialogConfirm)).performClick()
        compose.onNodeWithTag(EVENT_EDITOR_SUBMIT_TAG).performScrollTo().performClick()

        // Navigation must still wait for the actual server acknowledgement.
        compose.onNodeWithTag(EVENT_EDITOR_SUBMIT_TAG).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.events_tabUpcoming)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, repository.createCalls)
            repository.result.complete("created-event")
        }

        compose.onNodeWithText(str(R.string.events_tabUpcoming)).assertIsDisplayed()
        compose.onNodeWithTag(EVENT_EDITOR_SUBMIT_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(str(R.string.events_createSuccess), snackbar.currentSnackbarData?.visuals?.message)
            assertEquals(1, repository.createCalls)
        }
    }

    private class PendingCreateRepository : EventsRepository {
        val result = CompletableDeferred<String>()
        var createCalls = 0
        override suspend fun createEvent(input: CreateEventInput): String {
            createCalls++
            return result.await()
        }

        override fun observePublishedEvents() = flowOf<EventsListState>(EventsListState.Loaded(emptyList()))
        override fun observePastEvents() = flowOf<EventsListState>(EventsListState.Loaded(emptyList()))
        override fun observeEvent(eventId: String) = flowOf<EventSummary?>(null)
        override fun observeEventDetail(eventId: String) = flowOf<EventDetail?>(null)
        override fun observeMyRsvp(eventId: String, uid: String) = flowOf<RsvpStatus?>(null)
        override fun observeMyAttendance(eventId: String, uid: String) = flowOf<EventAttendanceStatus?>(null)
        override suspend fun checkIn(eventId: String, fix: CheckInFix): Nothing = error("Unexpected write")
        override suspend fun setRsvp(eventId: String, uid: String, status: RsvpStatus): Nothing = error("Unexpected write")
        override suspend fun updateEvent(eventId: String, input: CreateEventInput): Nothing = error("Unexpected write")
        override suspend fun cancelEvent(eventId: String, reason: String): Nothing = error("Unexpected write")
        override suspend fun loadAttendees(eventId: String): Nothing = error("Unexpected read")
    }
}
