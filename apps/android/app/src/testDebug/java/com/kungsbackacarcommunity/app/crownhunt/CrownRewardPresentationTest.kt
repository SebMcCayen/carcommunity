package com.kungsbackacarcommunity.app.crownhunt

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertTrue
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

    @Test
    fun missingAwardAmount_doesNotInventZeroOrTheBaseReward() {
        showOutcome(CrownSpawnClaimResult.AWARDED, null)
        compose.onNodeWithText(str(R.string.crownHunt_spawnResultAwardedPoints, 0)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.crownHunt_spawnResultAwardedPoints, 500)).assertDoesNotExist()
    }

    @Test
    fun englishReceipt_ordersActualCreditBalanceAndDailySnapshot_withoutResetMetadata() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(original.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val english = original.createConfigurationContext(config)
        val allowance = CrownAllowance(2250, 2193, Instant.parse("2026-03-29T22:00:00Z"))
        compose.setContent {
            CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides config) {
                KccTheme { Column { CrownRewardSummary(7, 207); CrownAllowanceText(allowance) } }
            }
        }
        val credit = compose.onNodeWithText(english.getString(R.string.crownHunt_spawnResultAwardedPoints, 7), useUnmergedTree = true)
        val balance = compose.onNodeWithText(english.getString(R.string.crownHunt_kpValue, 207), useUnmergedTree = true)
        val remaining = compose.onNodeWithText(english.getString(R.string.crownHunt_allowanceRemaining, 2193, 2250))
        credit.assertIsDisplayed(); balance.assertIsDisplayed(); remaining.assertIsDisplayed()
        assertTrue(credit.fetchSemanticsNode().boundsInRoot.bottom <= balance.fetchSemanticsNode().boundsInRoot.top)
        assertTrue(balance.fetchSemanticsNode().boundsInRoot.bottom <= remaining.fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("KP", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Stockholm", substring = true).assertDoesNotExist()
        compose.onNodeWithText("2026-03-29", substring = true).assertDoesNotExist()
    }

    @Test
    fun missingAwardAmount_preservesAnExplicitBalance() {
        showOutcome(CrownSpawnClaimResult.AWARDED, null, balance = 207)
        compose.onNodeWithText(str(R.string.crownHunt_kpValue, 207)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.crownHunt_spawnResultAwardedPoints, 0)).assertDoesNotExist()
    }

    private fun showOutcome(result: CrownSpawnClaimResult, points: Int?, balance: Int? = null) {
        compose.setContent {
            KccTheme {
                CrownSpawnPopup(
                    spawn = spawn,
                    state = CrownCollectState.Ready,
                    status = CrownClaimStatus.Done(CrownSpawnClaimOutcome(result, points, balance, spawn.rarity), spawn.id),
                    distanceMeters = 10.0,
                    onCollect = {}, onNavigate = {}, onDismiss = {},
                )
            }
        }
    }
}
