package com.kungsbackacarcommunity.app.subscription

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
class SubscriptionScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun setScreen(
        isActiveMember: Boolean,
        status: PurchaseFlowStatus,
        onManageSubscription: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            KccTheme {
                SubscriptionScreen(
                    isActiveMember = isActiveMember,
                    status = status,
                    canSubscribe = true,
                    onSubscribe = {},
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
            status = PurchaseFlowStatus.Success("plus"),
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
            status = PurchaseFlowStatus.Success("supporter"),
            onManageSubscription = { managedProductId = it },
        )

        composeTestRule
            .onNodeWithText(str(R.string.subscription_manageAction))
            .performClick()

        assertEquals(SUPPORTER_MONTHLY_PRODUCT_ID, managedProductId)
    }

    @Test
    fun genericMembership_withoutVerifiedPlayProduct_hidesManagementAction() {
        setScreen(
            isActiveMember = true,
            status = PurchaseFlowStatus.Idle,
        )

        composeTestRule
            .onNodeWithText(str(R.string.subscription_manageAction))
            .assertDoesNotExist()
    }
}
