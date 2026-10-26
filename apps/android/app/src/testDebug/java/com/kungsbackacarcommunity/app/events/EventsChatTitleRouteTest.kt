package com.kungsbackacarcommunity.app.events

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chat.ChatCoordinator
import com.kungsbackacarcommunity.app.chat.ChatMessagesState
import com.kungsbackacarcommunity.app.chat.ChatReportReason
import com.kungsbackacarcommunity.app.chat.EventChatRepository
import com.kungsbackacarcommunity.app.chat.EventChatRoute
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production EventsRoute → EventChatRoute → Screen title path. */
@RunWith(AndroidJUnit4::class)
class EventsChatTitleRouteTest {
    @get:Rule val compose = createComposeRule()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun freeEligibleViewerGetsLoadedTitle_andLiveRenamesWithoutAdditionalReads() {
        val events = LocalEvents()
        val chat = LocalChat()
        val coordinator = ChatCoordinator(chat)
        compose.setContent {
            KccTheme {
                EventsRoute(
                    repository = events, rsvpCoordinator = null, uid = "test-viewer",
                    passesMemberGate = true, canViewAttendees = false, isPaidSubscriber = false,
                    chatRepository = chat, chatCoordinator = coordinator, chatEnabled = true,
                    groupDriveRepository = null, groupDriveCoordinator = null,
                    initialEventId = "test-event", onBack = {}, errorReporter = null,
                )
            }
        }
        compose.onNodeWithTag(EVENT_DETAIL_CHAT_TAG).performScrollTo().performClick()
        compose.onNodeWithText("Loaded test title").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.chat_inputPlaceholder)).assertIsDisplayed()

        var readsBeforeRename: List<String> = emptyList()
        compose.runOnIdle {
            assertEquals(listOf("test-event"), chat.subscriptions)
            assertEquals(1, events.reads.count { it == "event:test-event" })
            readsBeforeRename = events.reads.toList()
            events.event.value = events.event.value.copy(title = "Renamed test title")
        }
        compose.onNodeWithText("Renamed test title").assertIsDisplayed()
        compose.onNodeWithText("Loaded test title").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(readsBeforeRename, events.reads)
            assertEquals(listOf("test-event"), chat.subscriptions)
        }
    }

    @Test
    fun existingIdOnlyCallerCanOmitTitle_andKeepsLocalizedGenericHeading() {
        val chat = LocalChat()
        compose.setContent {
            KccTheme {
                EventChatRoute(
                    repository = chat, coordinator = null, eventId = "test-event",
                    currentUid = "test-viewer", passesMemberGate = true,
                    eventStatus = EventStatus.PUBLISHED, myRsvp = RsvpStatus.GOING, onBack = {},
                )
            }
        }
        compose.onNodeWithText(str(R.string.chat_eventChatTitle)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("test-event"), chat.subscriptions) }
    }

    private class LocalChat : EventChatRepository {
        val subscriptions = mutableListOf<String>()
        override fun observeMessages(eventId: String) = flowOf<ChatMessagesState>(ChatMessagesState.Loaded(emptyList())).also {
            subscriptions += eventId
        }
        override suspend fun postMessage(eventId: String, message: String): Nothing = error("Unexpected write")
        override suspend fun reportMessage(eventId: String, messageId: String, reason: ChatReportReason, details: String?): Nothing =
            error("Unexpected write")
    }

    private class LocalEvents : EventsRepository {
        val reads = mutableListOf<String>()
        val event = MutableStateFlow(EventSummary(
            id = "test-event", title = "Loaded test title", summary = null,
            startsAtMillis = 1_793_466_000_000L, endsAtMillis = null, approximateArea = null,
            isOfficial = false, status = EventStatus.PUBLISHED, counts = RsvpCounts.EMPTY,
        ))
        override fun observePublishedEvents() = flowOf<EventsListState>(EventsListState.Loaded(emptyList())).also {
            reads += "published"
        }
        override fun observePastEvents() = flowOf<EventsListState>(EventsListState.Loaded(emptyList())).also { reads += "past" }
        override fun observeEvent(eventId: String) = event.also { reads += "event:$eventId" }
        override fun observeEventDetail(eventId: String) = flowOf<EventDetail?>(null).also { reads += "detail:$eventId" }
        override fun observeMyRsvp(eventId: String, uid: String) = flowOf<RsvpStatus?>(RsvpStatus.GOING).also { reads += "rsvp:$eventId" }
        override fun observeMyAttendance(eventId: String, uid: String) = flowOf<EventAttendanceStatus?>(null).also {
            reads += "attendance:$eventId"
        }
        override suspend fun loadAttendees(eventId: String): EventAttendeesResult = error("No paid roster read expected")
        override suspend fun checkIn(eventId: String, fix: CheckInFix): Nothing = error("Unexpected write")
        override suspend fun setRsvp(eventId: String, uid: String, status: RsvpStatus): Nothing = error("Unexpected write")
        override suspend fun createEvent(input: CreateEventInput): Nothing = error("Unexpected write")
        override suspend fun updateEvent(eventId: String, input: CreateEventInput): Nothing = error("Unexpected write")
        override suspend fun cancelEvent(eventId: String, reason: String): Nothing = error("Unexpected write")
    }
}
