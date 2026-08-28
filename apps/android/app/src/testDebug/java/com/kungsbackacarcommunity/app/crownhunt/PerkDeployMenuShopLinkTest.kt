package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.ui.test.assertIsDisplayed
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

/**
 * Robolectric-backed Compose UI test for the perk-deploy popup's "open the shop"
 * link (issue #1009): the link is shown, and tapping it dismisses the popup and
 * asks the host to open the shop — in that order (close first, then navigate).
 */
@RunWith(AndroidJUnit4::class)
class PerkDeployMenuShopLinkTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun showsShopLink_andTappingDismissesThenOpensShop() {
        val calls = mutableListOf<String>()
        composeTestRule.setContent {
            KccTheme {
                PerkDeployPopup(
                    menuState = PerkDeployMenuState.Loading,
                    status = PerkDeployStatus.Idle,
                    nowMillis = 0L,
                    onDeploy = {},
                    onDismiss = { calls += "dismiss" },
                    onOpenShop = { calls += "openShop" },
                )
            }
        }

        // The link is present, labelled from the localization contract.
        composeTestRule
            .onNodeWithText(str(R.string.crownHunt_deployOpenShop))
            .assertIsDisplayed()

        // Tapping it closes the popup THEN asks the host to open the shop.
        composeTestRule.onNodeWithTag(PERK_DEPLOY_OPEN_SHOP_TAG).performClick()

        assertEquals(listOf("dismiss", "openShop"), calls)
    }
}
