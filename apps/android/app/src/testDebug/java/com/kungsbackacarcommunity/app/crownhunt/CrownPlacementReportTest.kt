package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrownPlacementReportTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun canReportAnOutOfRangeCrown() {
        val spawn = CrownSpawn(
            id = "spawn",
            latitude = 57.5,
            longitude = 12.1,
            rarity = CrownRarity.RARE,
            rewardPoints = 100,
            collectRadiusMeters = 75.0,
            expiresAtMillis = Long.MAX_VALUE,
        )
        val requests = mutableListOf<Pair<CrownPlacementTarget, CrownPlacementReason>>()
        val reporter = CrownPlacementReporter { target, reason, _ -> requests.add(target to reason) }
        composeTestRule.setContent {
            KccTheme {
                CrownSpawnPopup(
                    spawn = spawn,
                    state = CrownCollectState.TooFar(320.0),
                    status = CrownClaimStatus.Idle,
                    distanceMeters = 320.0,
                    onCollect = {},
                    onNavigate = {},
                    onDismiss = {},
                    placementReporter = reporter,
                )
            }
        }
        composeTestRule.onNodeWithTag(CROWN_SPAWN_COLLECT_TAG).assertIsNotEnabled()
        composeTestRule.onNodeWithTag("crown_report_placement").performClick()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_crownPlacementSend)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_crownPlacementUnsafe)).performClick()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_crownPlacementSend)).performClick()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_crownPlacementReported)).assertIsDisplayed()
        assertEquals(listOf(CrownPlacementTarget("spawn", "automatic", 57.5, 12.1) to CrownPlacementReason.Unsafe), requests)
    }
}
