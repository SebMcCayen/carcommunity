package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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

/** Guard/callback regressions for the map-facing content moved to MapDetailSheet. */
@RunWith(AndroidJUnit4::class)
class CrownDockedSheetTest {
    @get:Rule val compose = createComposeRule()
    private val point = CrownHuntPoint("fixture-point", "Curated fixture", "Actual description", 100, 57.0, 12.0, 75.0)
    private fun str(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    @Test
    fun curatedPoint_outOfRange_cannotClaimButKeepsNavigation() {
        var claims = 0
        var navigations = 0
        compose.setContent {
            KccTheme {
                CrownPointPopup(point, CrownHuntClaimStatus.Idle, false,
                    { claims++ }, { navigations++ }, {})
            }
        }
        compose.onNodeWithTag(CROWN_POINT_POPUP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CROWN_POINT_COLLECT_TAG).performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(0, claims)
        compose.onNodeWithTag(CROWN_POINT_NAVIGATE_TAG).performScrollTo().performClick()
        assertEquals(1, navigations)
    }

    @Test
    fun curatedAward_usesClippedServerPointsAndBalance_andRemovesClaimActions() {
        compose.setContent {
            KccTheme {
                CrownPointPopup(point, CrownHuntClaimStatus.Done(ClaimOutcome(CrownHuntClaimResult.AWARDED, 7, 207)),
                    true, {}, {}, {})
            }
        }
        compose.onNodeWithText(str(R.string.crownHunt_spawnResultAwardedPoints, 7)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.crownHunt_kpValue, 207)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.crownHunt_spawnResultAwardedPoints, 100)).assertDoesNotExist()
        compose.onNodeWithTag(CROWN_POINT_COLLECT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CROWN_POINT_NAVIGATE_TAG).assertDoesNotExist()
    }

    @Test
    fun duplicateCuratedClaim_neverReplaysReceipt_evenWhenPayloadHasNumbers() {
        compose.setContent {
            KccTheme {
                CrownPointPopup(point, CrownHuntClaimStatus.Done(ClaimOutcome(CrownHuntClaimResult.ALREADY_CLAIMED, 7, 207)),
                    true, {}, {}, {})
            }
        }
        compose.onNodeWithText(str(R.string.crownHunt_spawnResultAwardedPoints, 7)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.crownHunt_serverConfirmed)).assertDoesNotExist()
        compose.onNodeWithTag(CROWN_POINT_COLLECT_TAG).assertDoesNotExist()
    }

    @Test
    fun activePerk_cannotBeActivatedAgain_afterMovingToDockedSheet() {
        val item = PerkDeployItem("shield", PerkKind.SHIELD, "Shield fixture", ownedCount = 2,
            activeUntilMillis = 90_000, activeTrapCount = 0, active = true, activatable = false)
        var calls = 0
        compose.setContent {
            KccTheme {
                PerkDeployPopup(PerkDeployMenuState.Loaded(listOf(item), false), PerkDeployStatus.Idle,
                    0, { calls++ }, {}, {})
            }
        }
        compose.onNodeWithTag(PERK_DEPLOY_POPUP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(perkDeployActivateTag("shield")).performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(0, calls)
    }
}
