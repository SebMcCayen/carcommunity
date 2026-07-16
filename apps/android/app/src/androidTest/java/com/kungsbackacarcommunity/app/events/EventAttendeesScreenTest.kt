package com.kungsbackacarcommunity.app.events

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
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
 * Compose UI tests for the event-detail attendee section: the rows, the
 * profile tap, and the honest non-loaded states.
 */
@RunWith(AndroidJUnit4::class)
class EventAttendeesScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun str(id: Int, arg: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, arg)

    private val publishedEvent =
        EventSummary(
            id = "e1",
            title = "Cars & Coffee",
            summary = "Monthly meet",
            startsAtMillis = 0L,
            endsAtMillis = null,
            approximateArea = "Kungsbacka",
            isOfficial = false,
            status = EventStatus.PUBLISHED,
            counts = RsvpCounts(going = 2, maybe = 3, notGoing = 1),
        )

    private fun setDetail(
        attendees: EventAttendeesState,
        onOpenMember: ((String) -> Unit)? = {},
        isActiveMember: Boolean = true,
    ) {
        composeTestRule.setContent {
            KccTheme {
                EventDetailScreen(
                    event = publishedEvent,
                    detail = null,
                    myRsvp = null,
                    isActiveMember = isActiveMember,
                    rsvpStatus = RsvpStatusUi.Idle,
                    onRsvp = {},
                    onBack = {},
                    attendees = attendees,
                    onOpenMember = onOpenMember,
                )
            }
        }
    }

    @Test
    fun attendees_rendersRowsForEachMember() {
        setDetail(
            EventAttendeesState.Loaded(
                listOf(
                    EventAttendee(uid = "u1", displayName = "Alice"),
                    EventAttendee(uid = "u2", displayName = "Bob"),
                ),
            ),
        )
        composeTestRule.onNodeWithText("Alice").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun attendees_tappingMember_opensTheirProfile() {
        var opened: String? = null
        setDetail(
            EventAttendeesState.Loaded(listOf(EventAttendee(uid = "u-42", displayName = "Alice"))),
            onOpenMember = { opened = it },
        )
        composeTestRule.onNodeWithTag(attendeeRowTag("u-42")).performScrollTo().performClick()
        assertEquals("u-42", opened)
    }

    @Test
    fun attendees_withoutAProfileRoute_rowHasNoClickAction() {
        // No member-profile repository => no route to open. The row still
        // renders the member, but must not advertise a tap that goes nowhere.
        setDetail(
            EventAttendeesState.Loaded(listOf(EventAttendee(uid = "u1", displayName = "Alice"))),
            onOpenMember = null,
        )
        composeTestRule
            .onNodeWithTag(attendeeRowTag("u1"))
            .performScrollTo()
            .assertHasNoClickAction()
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun attendees_withAProfileRoute_rowIsClickable() {
        setDetail(EventAttendeesState.Loaded(listOf(EventAttendee(uid = "u1", displayName = "Alice"))))
        composeTestRule
            .onNodeWithTag(attendeeRowTag("u1"))
            .performScrollTo()
            .assertHasClickAction()
    }

    @Test
    fun attendees_namelessMember_showsNeutralFallback() {
        setDetail(EventAttendeesState.Loaded(listOf(EventAttendee(uid = "u1", displayName = null))))
        composeTestRule
            .onNodeWithText(str(R.string.events_attendeesUnknownMember))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun attendees_unavailable_saysNamesAreNotShown_andStillShowsTheCount() {
        // The roster read is denied for a normal member today. The section must
        // say so plainly — and the public going tally is still reported.
        setDetail(EventAttendeesState.Unavailable)
        composeTestRule
            .onNodeWithText(str(R.string.events_attendeesUnavailable))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.events_attendeesCount, 2))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun attendees_empty_saysNobodyIsGoingYet() {
        setDetail(EventAttendeesState.Empty)
        composeTestRule
            .onNodeWithText(str(R.string.events_attendeesEmpty))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun attendees_error_isDistinctFromUnavailable() {
        setDetail(EventAttendeesState.Error)
        composeTestRule
            .onNodeWithText(str(R.string.events_attendeesError))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun attendees_hiddenFromNonMembers() {
        // Non-members see the membership gate, never the roster — same gate as
        // the exact location/description.
        setDetail(EventAttendeesState.Loaded(listOf(EventAttendee("u1", "Alice"))), isActiveMember = false)
        composeTestRule.onNodeWithTag(ATTENDEES_SECTION_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("Alice").assertDoesNotExist()
    }
}
