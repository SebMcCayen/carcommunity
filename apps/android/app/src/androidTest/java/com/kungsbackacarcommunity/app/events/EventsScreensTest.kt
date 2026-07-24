package com.kungsbackacarcommunity.app.events

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
 * Compose UI tests for the events list + detail screens (Phase 12 slice 9).
 */
@RunWith(AndroidJUnit4::class)
class EventsScreensTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun event(id: String = "e1", status: EventStatus = EventStatus.PUBLISHED) =
        EventSummary(
            id = id,
            title = "Cars & Coffee",
            summary = "Monthly meet",
            startsAtMillis = 0L,
            endsAtMillis = null,
            approximateArea = "Kungsbacka",
            isOfficial = true,
            status = status,
            counts = RsvpCounts(12, 3, 1),
        )

    @Test
    fun list_showsEmptyState() {
        composeTestRule.setContent {
            KccTheme {
                EventsListScreen(state = EventsListState.Loaded(emptyList()), onOpenEvent = {})
            }
        }
        composeTestRule.onNodeWithText(str(R.string.events_noUpcomingTitle)).assertIsDisplayed()
    }

    @Test
    fun list_tappingEvent_reportsId() {
        var opened: String? = null
        composeTestRule.setContent {
            KccTheme {
                EventsListScreen(
                    state = EventsListState.Loaded(listOf(event("evt-9"))),
                    onOpenEvent = { opened = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Cars & Coffee").performScrollTo().performClick()
        assertEquals("evt-9", opened)
    }

    @Test
    fun list_pastTab_showsItsOwnEmptyState_notTheUpcomingOne() {
        composeTestRule.setContent {
            KccTheme {
                EventsListScreen(
                    state = EventsListState.Loaded(emptyList()),
                    onOpenEvent = {},
                    tab = EventsListTab.PAST,
                    onSelectTab = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.events_noPastTitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.events_noUpcomingTitle)).assertDoesNotExist()
    }

    @Test
    fun list_pastTab_showsCompletedEventWithHeldBadge() {
        composeTestRule.setContent {
            KccTheme {
                EventsListScreen(
                    state =
                        EventsListState.Loaded(
                            listOf(event("done-1", status = EventStatus.COMPLETED)),
                        ),
                    onOpenEvent = {},
                    tab = EventsListTab.PAST,
                    onSelectTab = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Cars & Coffee").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.events_completedBadge))
            .performScrollTo()
            .assertIsDisplayed()
        // "Held" is not "Cancelled" — a completed event ran, it wasn't called off.
        composeTestRule.onNodeWithText(str(R.string.events_cancelledBadge)).assertDoesNotExist()
    }

    @Test
    fun list_publishedEvent_showsNoHeldBadge() {
        composeTestRule.setContent {
            KccTheme {
                EventsListScreen(
                    state = EventsListState.Loaded(listOf(event())),
                    onOpenEvent = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.events_completedBadge)).assertDoesNotExist()
    }

    @Test
    fun list_tappingPastTab_reportsSelection() {
        var selected: EventsListTab? = null
        composeTestRule.setContent {
            KccTheme {
                EventsListScreen(
                    state = EventsListState.Loaded(emptyList()),
                    onOpenEvent = {},
                    tab = EventsListTab.UPCOMING,
                    onSelectTab = { selected = it },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.events_tabPast)).performClick()
        assertEquals(EventsListTab.PAST, selected)
    }

    @Test
    fun list_pastTab_hidesTheCreateButton() {
        // "Create event" is offered on the upcoming tab and withheld on the
        // archive even though onCreateEvent is non-null in both cases.
        composeTestRule.setContent {
            KccTheme {
                EventsListScreen(
                    state = EventsListState.Loaded(emptyList()),
                    onOpenEvent = {},
                    tab = EventsListTab.PAST,
                    onSelectTab = {},
                    onCreateEvent = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.events_createButton)).assertDoesNotExist()
    }

    @Test
    fun list_upcomingTab_stillShowsTheCreateButton() {
        composeTestRule.setContent {
            KccTheme {
                EventsListScreen(
                    state = EventsListState.Loaded(emptyList()),
                    onOpenEvent = {},
                    tab = EventsListTab.UPCOMING,
                    onSelectTab = {},
                    onCreateEvent = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.events_createButton)).assertIsDisplayed()
    }

    @Test
    fun detail_member_canRsvp() {
        var answer: RsvpStatus? = null
        composeTestRule.setContent {
            KccTheme {
                EventDetailScreen(
                    event = event(),
                    detail = EventDetail(description = "Bring your car", address = null),
                    myRsvp = null,
                    passesMemberGate = true,
                    rsvpStatus = RsvpStatusUi.Idle,
                    onRsvp = { answer = it },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.events_rsvpGoing)).performScrollTo().performClick()
        assertEquals(RsvpStatus.GOING, answer)
    }

    @Test
    fun detail_nonMember_seesGate_andNoRsvp() {
        composeTestRule.setContent {
            KccTheme {
                EventDetailScreen(
                    event = event(),
                    detail = null,
                    myRsvp = null,
                    passesMemberGate = false,
                    rsvpStatus = RsvpStatusUi.Idle,
                    onRsvp = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.events_memberRequiredTitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.events_rsvpGoing)).assertDoesNotExist()
    }

    @Test
    fun detail_nullEvent_whileLoading_showsLoadingNotError() {
        composeTestRule.setContent {
            KccTheme {
                EventDetailScreen(
                    event = null,
                    detail = null,
                    myRsvp = null,
                    passesMemberGate = true,
                    rsvpStatus = RsvpStatusUi.Idle,
                    onRsvp = {},
                    onBack = {},
                    isLoading = true,
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.events_loadingDetail)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.events_errorDetail)).assertDoesNotExist()
    }

    @Test
    fun detail_member_cancelledEvent_showsNoDetailPlaceholderOrGate() {
        composeTestRule.setContent {
            KccTheme {
                EventDetailScreen(
                    event = event(status = EventStatus.CANCELLED),
                    detail = null,
                    myRsvp = null,
                    passesMemberGate = true,
                    rsvpStatus = RsvpStatusUi.Idle,
                    onRsvp = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.events_memberDetailPlaceholder)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.events_memberRequiredTitle)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.events_cancelledNotice)).assertIsDisplayed()
    }
}
