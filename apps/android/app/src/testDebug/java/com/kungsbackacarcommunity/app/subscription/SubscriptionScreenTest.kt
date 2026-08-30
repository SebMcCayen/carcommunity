package com.kungsbackacarcommunity.app.subscription

import androidx.compose.ui.test.assertIsDisplayed
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
    @get:Rule
    val composeTestRule = createComposeRule()

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

        composeTestRule
            .onNodeWithText(str(R.string.subscription_manageAction))
            .performClick()

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

        composeTestRule
            .onNodeWithText(str(R.string.subscription_manageAction))
            .assertDoesNotExist()
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
            .performClick()

        assertEquals(PLUS_MONTHLY_PRODUCT_ID, selectedProductId)
    }
}
