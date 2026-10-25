package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrownRewardPresentationTest {
    @get:Rule val compose = createComposeRule()

    private val spawn = CrownSpawn("test-spawn", 57.48, 12.07, CrownRarity.LEGENDARY, 500, 75.0, null)
    private fun str(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    @Test
    fun award_showsClippedServerPoints_andRemovesCollectAction() {
        showOutcome(CrownSpawnClaimResult.AWARDED, 7)
        compose.onNodeWithText(str(R.string.crownHunt_spawnResultAwardedPoints, 7)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.crownHunt_spawnResultAwardedPoints, 500)).assertDoesNotExist()
        compose.onNodeWithTag(CROWN_SPAWN_COLLECT_TAG).assertDoesNotExist()
    }

    @Test
    fun duplicate_doesNotRenderReward_evenIfPayloadCarriesPoints() {
        showOutcome(CrownSpawnClaimResult.ALREADY_COLLECTED, 7)
        compose.onNodeWithText(str(CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.ALREADY_COLLECTED)))
            .assertIsDisplayed()
        compose.onNodeWithText(str(R.string.crownHunt_spawnResultAwardedPoints, 7)).assertDoesNotExist()
        compose.onNodeWithTag(CROWN_SPAWN_COLLECT_TAG).assertDoesNotExist()
    }

    private fun showOutcome(result: CrownSpawnClaimResult, points: Int) {
        compose.setContent {
            KccTheme {
                CrownSpawnPopup(
                    spawn = spawn,
                    state = CrownCollectState.Ready,
                    status = CrownClaimStatus.Done(CrownSpawnClaimOutcome(result, points, null, spawn.rarity), spawn.id),
                    distanceMeters = 10.0,
                    onCollect = {}, onNavigate = {}, onDismiss = {},
                )
            }
        }
    }
}
