package com.kungsbackacarcommunity.app.events

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression coverage for the real S41–S43 controls, independent of live services. */
@RunWith(AndroidJUnit4::class)
class EventDesignRegressionTest {
    @get:Rule val compose = createComposeRule()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val event = EventSummary(
        id = "test-event", title = "Test event", summary = null,
        startsAtMillis = 1_793_466_000_000L, endsAtMillis = null,
        approximateArea = "Legacy area", locationName = "Actual meeting place",
        isOfficial = false, status = EventStatus.PUBLISHED, counts = RsvpCounts(4, 2, 1),
    )
    private val input = CreateEventInput(
        title = "Test title", description = "Test description", address = "Test address",
        startsAtMillis = 1_793_466_000_000L, latitude = 57.48, longitude = 12.07,
    )

    @Test
    fun listUsesPublicPlaceName_andFallsBackToLegacyArea() {
        compose.setContent {
            KccTheme {
                EventsListScreen(
                    state = EventsListState.Loaded(listOf(event, event.copy(id = "legacy", locationName = null))),
                    onOpenEvent = {},
                )
            }
        }
        compose.onNodeWithText("Actual meeting place").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Legacy area").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun freeDetailKeepsAllThreeContextualCallbacks_andSelectedRsvp() {
        val actions = mutableListOf<String>()
        compose.setContent {
            KccTheme {
                EventDetailScreen(
                    event = event, detail = EventDetail("Full free description", "Exact free address"),
                    myRsvp = RsvpStatus.GOING, passesMemberGate = true, canViewAttendees = false,
                    rsvpStatus = RsvpStatusUi.Idle, onRsvp = {}, onBack = {},
                    onOpenChat = { actions += "chat" }, onOpenGroupDrive = { actions += "drive" },
                    onShareEvent = { actions += "share" },
                )
            }
        }
        compose.onNodeWithTag(EVENT_RSVP_SELECTOR_TAG).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(EVENT_DETAIL_CHAT_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(EVENT_DETAIL_GROUP_DRIVE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(EVENT_DETAIL_SHARE_TAG).performScrollTo().performClick()
        assertEquals(listOf("chat", "drive", "share"), actions)
        compose.onNodeWithText("Full free description").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Exact free address").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(EVENT_DETAIL_REVEAL_ATTENDEES_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CHECK_IN_BUTTON_TAG).assertDoesNotExist()
    }

    @Test
    fun savingRsvpDisablesEveryResponse_andUnwiredActionsStayAbsent() {
        compose.setContent {
            KccTheme {
                EventDetailScreen(event, null, null, true, RsvpStatusUi.Saving, {}, {})
            }
        }
        compose.onNodeWithTag(EVENT_RSVP_SELECTOR_TAG).performScrollTo().assertIsNotEnabled()
        RsvpStatus.entries.forEach { answer -> compose.onNodeWithTag(rsvpButtonTag(answer)).assertDoesNotExist() }
        compose.onNodeWithTag(EVENT_DETAIL_CHAT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EVENT_DETAIL_GROUP_DRIVE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EVENT_DETAIL_SHARE_TAG).assertDoesNotExist()
    }

    @Test
    fun selectorOffersEveryRealRsvpAnswer_withoutWritingOnOpen() {
        val answers = mutableListOf<RsvpStatus>()
        compose.setContent {
            KccTheme {
                EventDetailScreen(event, null, null, true, RsvpStatusUi.Idle, { answers += it }, {})
            }
        }
        RsvpStatus.entries.forEachIndexed { index, answer ->
            compose.onNodeWithTag(EVENT_RSVP_SELECTOR_TAG).performScrollTo().performClick()
            assertEquals(index, answers.size)
            compose.onNodeWithTag(rsvpButtonTag(answer)).performClick()
        }
        assertEquals(RsvpStatus.entries.toList(), answers)
    }

    @Test
    fun paidRosterRemainsDeferredUntilExplicitTap() {
        var reveals = 0
        compose.setContent {
            KccTheme {
                EventDetailScreen(
                    event, null, RsvpStatus.GOING, true, RsvpStatusUi.Idle, {}, {},
                    canViewAttendees = true, onRevealAttendees = { reveals++ },
                )
            }
        }
        assertEquals(0, reveals)
        compose.onNodeWithTag(EVENT_DETAIL_REVEAL_ATTENDEES_TAG).performScrollTo().performClick()
        assertEquals(1, reveals)
        compose.onNodeWithTag(EVENT_DETAIL_UPGRADE_TAG).assertDoesNotExist()
    }

    @Test
    fun editorSavingDisablesFieldsDateAndSubmit() {
        compose.setContent { KccTheme { CreateEventScreen(true, null, {}, {}, initialInput = input) } }
        listOf(EVENT_EDITOR_TITLE_TAG, EVENT_EDITOR_DESCRIPTION_TAG, EVENT_EDITOR_ADDRESS_TAG,
            EVENT_EDITOR_START_TAG, EVENT_EDITOR_SUBMIT_TAG).forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
    }

    @Test
    fun editorSubmitsExistingValues_andLeavesPublicSiteOffByDefault() {
        var submitted: CreateEventInput? = null
        compose.setContent {
            KccTheme { CreateEventScreen(false, null, { submitted = it }, {}, initialInput = input) }
        }
        assertNull(submitted)
        compose.onNodeWithTag(EVENT_EDITOR_SUBMIT_TAG).performScrollTo().performClick()
        assertEquals(input, submitted)
    }

    @Test
    fun editorEditHidesCreateOnlyPublicSite_andLivePublicationCopy() {
        var submitted: CreateEventInput? = null
        compose.setContent {
            KccTheme { CreateEventScreen(false, null, { submitted = it }, {}, initialInput = input, isEdit = true) }
        }
        compose.onNodeWithText(str(R.string.events_createPublicSiteLabel)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.events_createLiveNotice)).assertDoesNotExist()
        compose.onNodeWithTag(EVENT_EDITOR_SUBMIT_TAG).performScrollTo().performClick()
        assertEquals(input, submitted)
    }

    @Test
    fun editorRejectsOverlongTitleWithoutWriting() {
        var writes = 0
        compose.setContent {
            KccTheme { CreateEventScreen(false, null, { writes++ }, {}, initialInput = input.copy(title = "x".repeat(Events.TITLE_MAX + 1))) }
        }
        compose.onNodeWithTag(EVENT_EDITOR_SUBMIT_TAG).performScrollTo().performClick()
        assertEquals(0, writes)
        compose.onNodeWithText(str(R.string.events_createValidation)).performScrollTo().assertIsDisplayed()
    }
}
