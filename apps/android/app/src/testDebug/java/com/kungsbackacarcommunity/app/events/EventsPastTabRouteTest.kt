package com.kungsbackacarcommunity.app.events

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Route-level cover for the past/archive tab: proves the tab is wired to a
 * DIFFERENT query rather than being cosmetic chrome over the published list.
 *
 * The fake serves disjoint rows from [EventsRepository.observePublishedEvents]
 * and [EventsRepository.observePastEvents], so which rows render is direct
 * evidence of which query the route subscribed to.
 */
@RunWith(AndroidJUnit4::class)
class EventsPastTabRouteTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun summary(id: String, title: String, status: EventStatus, startsAtMillis: Long) =
        EventSummary(
            id = id,
            title = title,
            summary = null,
            startsAtMillis = startsAtMillis,
            endsAtMillis = null,
            approximateArea = "Kungsbacka",
            isOfficial = false,
            status = status,
            counts = RsvpCounts.EMPTY,
        )

    /** Records which list queries were subscribed to, and serves disjoint rows. */
    private class FakeRepo(
        private val published: List<EventSummary>,
        private val past: List<EventSummary>,
    ) : EventsRepository {
        val subscribed = mutableListOf<String>()

        override fun observePublishedEvents(): Flow<EventsListState> {
            subscribed += "published"
            return flowOf(EventsListState.Loaded(published))
        }

        override fun observePastEvents(): Flow<EventsListState> {
            subscribed += "past"
            return flowOf(EventsListState.Loaded(past))
        }

        override fun observeEvent(eventId: String): Flow<EventSummary?> = flowOf(null)

        override fun observeEventDetail(eventId: String): Flow<EventDetail?> = flowOf(null)

        override fun observeMyRsvp(eventId: String, uid: String): Flow<RsvpStatus?> = flowOf(null)

        override fun observeMyAttendance(
            eventId: String,
            uid: String,
        ): Flow<EventAttendanceStatus?> = flowOf(null)

        override suspend fun checkIn(eventId: String, fix: CheckInFix): CheckInResult =
            CheckInResult.UNKNOWN

        override suspend fun setRsvp(eventId: String, uid: String, status: RsvpStatus) = Unit

        override suspend fun createEvent(input: CreateEventInput): String = "new-id"

        override suspend fun updateEvent(eventId: String, input: CreateEventInput) = Unit

        override suspend fun cancelEvent(eventId: String, reason: String) = Unit

        override suspend fun loadAttendees(eventId: String): EventAttendeesResult =
            EventAttendeesResult.Loaded(emptyList())
    }

    private fun setContent(repo: EventsRepository) {
        composeTestRule.setContent {
            KccTheme {
                EventsRoute(
                    repository = repo,
                    rsvpCoordinator = null,
                    uid = "u1",
                    passesMemberGate = true,
                    chatRepository = null,
                    chatCoordinator = null,
                    chatEnabled = false,
                    groupDriveRepository = null,
                    groupDriveCoordinator = null,
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun pastTab_swapsTheUpcomingListForTheCompletedOne() {
        val repo =
            FakeRepo(
                published = listOf(summary("p1", "Upcoming meet", EventStatus.PUBLISHED, 9_000L)),
                past = listOf(summary("c1", "Ended meet", EventStatus.COMPLETED, 1_000L)),
            )
        setContent(repo)

        // Upcoming tab is the landing state.
        composeTestRule.onNodeWithText("Upcoming meet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ended meet").assertDoesNotExist()

        composeTestRule.onNodeWithText(str(R.string.events_tabPast)).performClick()

        // The completed event is now browsable — the whole point of the change.
        composeTestRule.onNodeWithText("Ended meet").assertIsDisplayed()
        // ...and the published one is NOT bleeding through, which is what a
        // tab that merely re-labels the same query would do.
        composeTestRule.onNodeWithText("Upcoming meet").assertDoesNotExist()
        assertTrue(
            "past query was never subscribed: ${repo.subscribed}",
            repo.subscribed.contains("past"),
        )
    }

    @Test
    fun pastTab_withNoCompletedEvents_showsThePastEmptyState() {
        val repo =
            FakeRepo(
                published = listOf(summary("p1", "Upcoming meet", EventStatus.PUBLISHED, 9_000L)),
                past = emptyList(),
            )
        setContent(repo)

        composeTestRule.onNodeWithText(str(R.string.events_tabPast)).performClick()

        composeTestRule.onNodeWithText(str(R.string.events_noPastTitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.events_noUpcomingTitle)).assertDoesNotExist()
    }

    @Test
    fun returningToUpcoming_restoresTheUpcomingQuery() {
        val repo =
            FakeRepo(
                published = listOf(summary("p1", "Upcoming meet", EventStatus.PUBLISHED, 9_000L)),
                past = listOf(summary("c1", "Ended meet", EventStatus.COMPLETED, 1_000L)),
            )
        setContent(repo)

        composeTestRule.onNodeWithText(str(R.string.events_tabPast)).performClick()
        composeTestRule.onNodeWithText(str(R.string.events_tabUpcoming)).performClick()

        composeTestRule.onNodeWithText("Upcoming meet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ended meet").assertDoesNotExist()
    }

    @Test
    fun openingAPastEvent_navigatesToDetail() {
        // A row in the archive is a real link, not a dead label: tapping it
        // leaves the list (the tab row goes away) for the detail screen.
        val repo =
            FakeRepo(
                published = emptyList(),
                past = listOf(summary("c1", "Ended meet", EventStatus.COMPLETED, 1_000L)),
            )
        setContent(repo)

        composeTestRule.onNodeWithText(str(R.string.events_tabPast)).performClick()
        composeTestRule.onNodeWithText("Ended meet").performClick()

        composeTestRule.onNodeWithText(str(R.string.events_tabPast)).assertDoesNotExist()
        assertEquals(listOf("published", "past"), repo.subscribed.distinct())
    }
}
