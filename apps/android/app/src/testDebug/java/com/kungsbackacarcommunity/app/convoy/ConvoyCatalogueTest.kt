package com.kungsbackacarcommunity.app.convoy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConvoyCatalogueTest {
    @get:Rule val compose = createComposeRule()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun convoy(status: ConvoyStatus = ConvoyStatus.Active) = ConvoySummary(
        convoyId = "test-convoy", ownerUid = "test-owner", title = "Test convoy", status = status,
        members = listOf(ConvoyMember("test-owner", ConvoyRole.Owner, ConvoyInviteStatus.Accepted, null, "Test owner", null)),
        memberUids = listOf("test-owner"), viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted),
        livePositionUids = emptyList(), summary = null, createdAt = null, startedAt = null, endedAt = null,
    )

    @Test
    fun suppliedDestinationIsReadable_withoutAddingUnavailableNavigationActions() {
        compose.setContent {
            KccTheme {
                ConvoyDetailScreen(
                    convoy().copy(destination = ConvoyDestination(57.48, 12.07, "Actual test destination", "test-owner", null, null)),
                    false, null, {}, {}, {},
                )
            }
        }
        compose.onNodeWithTag(CONVOY_DETAIL_DESTINATION_TAG).assertIsDisplayed()
        compose.onNodeWithText("Actual test destination").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.convoy_barDestinationNavigate)).assertDoesNotExist()
    }

    @Test
    fun malformedDateAndAbsentDestinationDoNotInventMetadata() {
        compose.setContent {
            KccTheme { ConvoyDetailScreen(convoy().copy(startedAt = "invalid"), false, null, {}, {}, {}) }
        }
        compose.onNodeWithTag(CONVOY_DETAIL_DATE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CONVOY_DETAIL_DESTINATION_TAG).assertDoesNotExist()
        compose.onNodeWithText("invalid").assertDoesNotExist()
    }

    @Test
    fun endedRecapUsesServerCountsAndUnavailableDistance_andOmitsSessionExits() {
        compose.setContent {
            KccTheme {
                ConvoyDetailScreen(
                    convoy(ConvoyStatus.Ended).copy(
                        endedAt = "2026-10-24T17:00:00Z",
                        summary = ConvoySummaryStats(3_600L, listOf("test-owner"), 7, null),
                    ),
                    false, null, {}, {}, {}, onLeave = {},
                )
            }
        }
        compose.onNodeWithTag(CONVOY_DETAIL_DATE_TAG).assertIsDisplayed()
        compose.onNodeWithText("7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.convoy_summaryDistanceUnavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CONVOY_DETAIL_END_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CONVOY_DETAIL_LEAVE_TAG).assertDoesNotExist()
    }

    @Test
    fun endedSectionKeepsItsOpenCallback_andActiveMembershipStillBlocksCreate() {
        var opened: String? = null
        val ended = convoy(ConvoyStatus.Ended).copy(convoyId = "test-ended", title = "Ended test convoy")
        compose.setContent {
            KccTheme {
                ConvoyListScreen(
                    status = ConvoyListStatus.Loaded(listOf(ended, convoy()), emptyList()),
                    refreshError = null, actionError = null, busyConvoys = emptySet(),
                    onCreate = {}, onOpenConvoy = { opened = it }, onAccept = {}, onDecline = {},
                    onClearActionError = {}, onRefresh = {},
                )
            }
        }
        compose.onNodeWithText(str(R.string.convoy_createAction)).assertIsNotEnabled()
        compose.onNodeWithTag(CONVOY_LIST_TAG).performScrollToNode(hasText("Ended test convoy"))
        compose.onNodeWithText("Ended test convoy").performClick()
        assertEquals("test-ended", opened)
    }
}
