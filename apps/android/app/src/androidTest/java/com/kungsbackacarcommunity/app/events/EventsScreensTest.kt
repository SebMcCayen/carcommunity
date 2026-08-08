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

    private fun str(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun event(
        id: String = "e1",
        status: EventStatus = EventStatus.PUBLISHED,
        locationName: String? = null,
    ) =
        EventSummary(
            id = id,
            title = "Cars & Coffee",
            summary = "Monthly meet",
            startsAtMillis = 0L,
            endsAtMillis = null,
            approximateArea = "Kungsbacka",
            locationName = locationName,
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
    fun list_showsGoingCountOnEachRow() {
        // The row shows the RSVP "going" tally (people who marked themselves
        // attending), on the trailing side. The fixture has going = 12.
        composeTestRule.setContent {
            KccTheme {
                EventsListScreen(
                    state = EventsListState.Loaded(listOf(event())),
                    onOpenEvent = {},
                )
            }
        }
        composeTestRule
            .onNodeWithText(str(R.string.events_rowGoingCount, 12))
            .performScrollTo()
            .assertIsDisplayed()
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
        // Target the RSVP action BUTTON by its tag: its "Going" label now also
        // appears as a count in the RSVP breakdown, so onNodeWithText("Going")
        // would match two nodes.
        composeTestRule.onNodeWithTag(rsvpButtonTag(RsvpStatus.GOING)).performScrollTo().performClick()
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
        // Non-members get no RSVP block at all: neither the action button nor the
        // count breakdown (both gated on canRsvp).
        composeTestRule.onNodeWithTag(rsvpButtonTag(RsvpStatus.GOING)).assertDoesNotExist()
        composeTestRule.onNodeWithTag(RSVP_COUNTS_BREAKDOWN_TAG).assertDoesNotExist()
    }

    /**
     * The place name is PUBLIC teaser data since the 2026-07 open-up (it labels
     * the map pin every signed-in user can see), so a non-member who opens the
     * detail from a pin must still be told WHERE the event is — only the precise
     * street address and the long description stay behind the member gate.
     */
    @Test
    fun detail_nonMember_stillSeesThePublicPlaceName() {
        composeTestRule.setContent {
            KccTheme {
                EventDetailScreen(
                    event = event(locationName = "Kungsbacka torg"),
                    detail = null,
                    myRsvp = null,
                    passesMemberGate = false,
                    rsvpStatus = RsvpStatusUi.Idle,
                    onRsvp = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithTag(EVENT_DETAIL_LOCATION_NAME_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Kungsbacka torg").assertIsDisplayed()
        // The gate itself is still there — this test asserts the place name is
        // outside it, not that the gate is gone.
        composeTestRule.onNodeWithText(str(R.string.events_memberRequiredTitle)).assertIsDisplayed()
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

    @Test
    fun detail_checkInAvailable_showsWithinAreaHelperText() {
        composeTestRule.setContent {
            KccTheme {
                EventDetailScreen(
                    event = event().copy(latitude = 57.4874, longitude = 12.0757),
                    detail = null,
                    myRsvp = null,
                    passesMemberGate = true,
                    rsvpStatus = RsvpStatusUi.Idle,
                    onRsvp = {},
                    onBack = {},
                    checkInAvailable = true,
                    onCheckIn = {},
                )
            }
        }
        // The button is offered, and the geofence requirement is spelled out beside it.
        composeTestRule.onNodeWithTag(CHECK_IN_BUTTON_TAG).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(CHECK_IN_WITHIN_AREA_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun detail_shareButton_isShown_andInvokesCallback() {
        var shared = false
        composeTestRule.setContent {
            KccTheme {
                EventDetailScreen(
                    event = event(),
                    detail = null,
                    myRsvp = null,
                    passesMemberGate = true,
                    rsvpStatus = RsvpStatusUi.Idle,
                    onRsvp = {},
                    onBack = {},
                    onShareEvent = { shared = true },
                )
            }
        }
        composeTestRule.onNodeWithTag(EVENT_DETAIL_SHARE_TAG).performScrollTo().performClick()
        assertEquals(true, shared)
    }

    @Test
    fun detail_navigateButton_isShown_whenEventHasCoordinates() {
        var navigated = false
        composeTestRule.setContent {
            KccTheme {
                EventDetailScreen(
                    event = event().copy(latitude = 57.4874, longitude = 12.0757),
                    detail = null,
                    myRsvp = null,
                    passesMemberGate = true,
                    rsvpStatus = RsvpStatusUi.Idle,
                    onRsvp = {},
                    onBack = {},
                    onNavigate = { navigated = true },
                    // hasMapToken = false so no Mapbox surface renders in the test —
                    // the Navigate button does not depend on the token.
                    hasMapToken = false,
                )
            }
        }
        composeTestRule.onNodeWithTag(EVENT_DETAIL_NAVIGATE_TAG).performScrollTo().performClick()
        assertEquals(true, navigated)
    }

    @Test
    fun detail_noCoordinates_hidesNavigateButton() {
        composeTestRule.setContent {
            KccTheme {
                EventDetailScreen(
                    event = event(), // no latitude/longitude
                    detail = null,
                    myRsvp = null,
                    passesMemberGate = true,
                    rsvpStatus = RsvpStatusUi.Idle,
                    onRsvp = {},
                    onBack = {},
                    onNavigate = { },
                )
            }
        }
        composeTestRule.onNodeWithTag(EVENT_DETAIL_NAVIGATE_TAG).assertDoesNotExist()
    }

    @Test
    fun detail_showsOrganizer_whenNameResolved() {
        composeTestRule.setContent {
            KccTheme {
                EventDetailScreen(
                    event = event(),
                    detail = null,
                    myRsvp = null,
                    passesMemberGate = true,
                    rsvpStatus = RsvpStatusUi.Idle,
                    onRsvp = {},
                    onBack = {},
                    organizerName = "Alice",
                )
            }
        }
        composeTestRule.onNodeWithTag(EVENT_DETAIL_ORGANIZER_TAG).performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.events_organizerLabel, "Alice"))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun detail_hidesOrganizer_whenNameAbsent() {
        composeTestRule.setContent {
            KccTheme {
                EventDetailScreen(
                    event = event(),
                    detail = null,
                    myRsvp = null,
                    passesMemberGate = true,
                    rsvpStatus = RsvpStatusUi.Idle,
                    onRsvp = {},
                    onBack = {},
                    organizerName = null,
                )
            }
        }
        composeTestRule.onNodeWithTag(EVENT_DETAIL_ORGANIZER_TAG).assertDoesNotExist()
    }
}
