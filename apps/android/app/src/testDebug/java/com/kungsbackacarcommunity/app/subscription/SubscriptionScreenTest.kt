package com.kungsbackacarcommunity.app.subscription

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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

@RunWith(AndroidJUnit4::class)
class SubscriptionScreenTest {
    @get:Rule val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun setScreen(
        isActiveMember: Boolean,
        currentTier: String? = null,
        status: PurchaseFlowStatus,
        canChangePlan: Boolean = false,
        canManageSubscription: Boolean = false,
        onSubscribe: (String) -> Unit = {},
        onManageSubscription: (String?) -> Unit = {},
    ) {
        composeTestRule.setContent {
            KccTheme {
                SubscriptionScreen(
                    isActiveMember = isActiveMember,
                    currentTier = currentTier,
                    status = status,
                    canSubscribe = true,
                    canChangePlan = canChangePlan,
                    canManageSubscription = canManageSubscription,
                    onSubscribe = onSubscribe,
                    onManageSubscription = onManageSubscription,
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun verifiedPlus_showsManagementActionForPlusProduct() {
        var managedProductId: String? = null
        setScreen(
            isActiveMember = true,
            currentTier = "plus",
            status = PurchaseFlowStatus.Success("plus"),
            canManageSubscription = true,
            onManageSubscription = { managedProductId = it },
        )

        composeTestRule
            .onNodeWithText(str(R.string.subscription_manageAction))
            .assertIsDisplayed()
            .performClick()

        assertEquals(PLUS_MONTHLY_PRODUCT_ID, managedProductId)
    }

    @Test
    fun verifiedSupporter_managesSupporterProduct() {
        var managedProductId: String? = null
        setScreen(
            isActiveMember = true,
            currentTier = "supporter",
            status = PurchaseFlowStatus.Success("supporter"),
            canManageSubscription = true,
            onManageSubscription = { managedProductId = it },
        )

        composeTestRule.onNodeWithText(str(R.string.subscription_manageAction)).performClick()

        assertEquals(SUPPORTER_MONTHLY_PRODUCT_ID, managedProductId)
    }

    @Test
    fun playMembership_withoutKnownProduct_opensGenericManagement() {
        var managedProductId: String? = "not-called"
        setScreen(
            isActiveMember = true,
            status = PurchaseFlowStatus.Idle,
            canManageSubscription = true,
            onManageSubscription = { managedProductId = it },
        )

        composeTestRule
            .onNodeWithText(str(R.string.subscription_manageAction))
            .assertIsDisplayed()
            .performClick()

        assertEquals(null, managedProductId)
    }

    @Test
    fun manualMembership_doesNotOfferGooglePlayManagement() {
        setScreen(
            isActiveMember = true,
            status = PurchaseFlowStatus.Idle,
            canManageSubscription = false,
        )

        composeTestRule.onNodeWithText(str(R.string.subscription_manageAction)).assertDoesNotExist()
    }

    @Test
    fun restoredPlus_fromBackend_showsTierAndCanUpgrade() {
        var selectedProductId: String? = null
        setScreen(
            isActiveMember = true,
            currentTier = "plus",
            status = PurchaseFlowStatus.Idle,
            canChangePlan = true,
            onSubscribe = { selectedProductId = it },
        )

        composeTestRule
            .onNodeWithText(str(R.string.subscription_currentEntitlementPlus))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.subscription_upgradeSupporterAction))
            .performScrollTo()
            .performClick()

        assertEquals(SUPPORTER_MONTHLY_PRODUCT_ID, selectedProductId)
    }

    @Test
    fun restoredSupporter_canSchedulePlusDowngrade() {
        var selectedProductId: String? = null
        setScreen(
            isActiveMember = true,
            currentTier = "supporter",
            status = PurchaseFlowStatus.Idle,
            canChangePlan = true,
            onSubscribe = { selectedProductId = it },
        )

        composeTestRule
            .onNodeWithText(str(R.string.subscription_downgradePlusAction))
            .performScrollTo()
            .performClick()

        assertEquals(PLUS_MONTHLY_PRODUCT_ID, selectedProductId)
    }

    @Test
    fun planningPrices_areNotRenderedAsStorePrices() {
        var purchasedProductId: String? = null
        setScreen(
            isActiveMember = false,
            status = PurchaseFlowStatus.Idle,
            onSubscribe = { purchasedProductId = it },
        )
        composeTestRule.onNodeWithText(str(R.string.subscription_plusTitle)).assertDoesNotExist()
        composeTestRule
            .onNodeWithText(str(R.string.subscription_supporterTitle))
            .assertDoesNotExist()
        composeTestRule
            .onNode(hasText(str(R.string.subscription_subscribePlusAction)) and hasClickAction())
            .performScrollTo()
            .performClick()
        assertEquals(PLUS_MONTHLY_PRODUCT_ID, purchasedProductId)
    }

    @Test
    fun explicitRestore_usesCallback_andIsDisabledDuringVerification() {
        var restores = 0
        var status by
            androidx.compose.runtime.mutableStateOf<PurchaseFlowStatus>(PurchaseFlowStatus.Idle)
        composeTestRule.setContent {
            KccTheme {
                SubscriptionScreen(
                    isActiveMember = false,
                    currentTier = null,
                    status = status,
                    canSubscribe = true,
                    canChangePlan = false,
                    canManageSubscription = false,
                    onSubscribe = {},
                    onManageSubscription = {},
                    onBack = {},
                    onRestore = { restores++ },
                )
            }
        }
        composeTestRule
            .onNodeWithText(str(R.string.subscription_restoreAction))
            .performScrollTo()
            .performClick()
        assertEquals(1, restores)
        composeTestRule.runOnIdle { status = PurchaseFlowStatus.Verifying }
        composeTestRule
            .onNodeWithText(str(R.string.subscription_restoreAction))
            .assertIsNotEnabled()
        composeTestRule
            .onNode(hasText(str(R.string.subscription_subscribePlusAction)) and hasClickAction())
            .assertIsNotEnabled()
        composeTestRule
            .onNode(
                hasText(str(R.string.subscription_subscribeSupporterAction)) and hasClickAction()
            )
            .assertIsNotEnabled()
    }
}
