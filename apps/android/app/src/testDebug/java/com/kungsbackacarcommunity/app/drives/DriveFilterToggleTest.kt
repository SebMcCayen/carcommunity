package com.kungsbackacarcommunity.app.drives

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility contract for the collapsed History filter header.
 *
 * Collapsing the filters hides WHICH filters are on, so the active-filter count is
 * the only thing telling a member that drives are being hidden from them. The
 * header's KDoc claims two things about how that count reaches TalkBack — the
 * header speaks it, and the badge carries no semantics of its own — and a claim no
 * test enforces is exactly how this drifts back to a silent (or stuttering) badge.
 *
 * So both halves are pinned here: the count must be announced EXACTLY once, never
 * zero times and never twice.
 */
@RunWith(AndroidJUnit4::class)
class DriveFilterToggleTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun str(id: Int, arg: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, arg)

    private fun setToggle(activeFilterCount: Int, expanded: Boolean = false) {
        composeTestRule.setContent {
            KccTheme {
                DriveFilterToggle(
                    expanded = expanded,
                    activeFilterCount = activeFilterCount,
                    onToggle = {},
                )
            }
        }
    }

    @Test
    fun headerSpeaksTheActiveFilterCount() {
        setToggle(activeFilterCount = 2)

        composeTestRule
            .onNodeWithTag(DRIVE_FILTER_TOGGLE_TAG)
            .assertContentDescriptionEquals(
                str(R.string.savedDrives_filterToggleExpand) +
                    ", " +
                    str(R.string.savedDrives_filterActiveCount, 2),
            )
    }

    @Test
    fun badgeContributesNoSemanticsOfItsOwn() {
        setToggle(activeFilterCount = 2)

        // Unmerged tree: the pill's digit must not exist as a semantics node at all.
        // If it did, the merged header node would carry the count twice — once in the
        // contentDescription above and once as text folded up from the badge.
        composeTestRule.onAllNodesWithText("2", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun noCountIsSpokenWhenNoFiltersAreActive() {
        setToggle(activeFilterCount = 0)

        // Zero active filters draws no badge, so the header falls back to the bare
        // action label — it must not announce "Active filters: 0".
        composeTestRule
            .onNodeWithTag(DRIVE_FILTER_TOGGLE_TAG)
            .assertContentDescriptionEquals(str(R.string.savedDrives_filterToggleExpand))
    }

    @Test
    fun expandedHeaderOffersTheCollapseAction() {
        setToggle(activeFilterCount = 0, expanded = true)

        composeTestRule
            .onNodeWithTag(DRIVE_FILTER_TOGGLE_TAG)
            .assertContentDescriptionEquals(str(R.string.savedDrives_filterToggleCollapse))
    }
}
