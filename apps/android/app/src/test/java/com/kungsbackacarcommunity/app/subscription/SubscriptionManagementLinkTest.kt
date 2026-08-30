package com.kungsbackacarcommunity.app.subscription

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionManagementLinkTest {
    private val applicationId = "com.kungsbackacarcommunity.app"

    @Test
    fun `plus uri opens the product-specific management page`() {
        assertEquals(
            "https://play.google.com/store/account/subscriptions" +
                "?sku=plus_monthly&package=com.kungsbackacarcommunity.app",
            SubscriptionManagementLink.webUri(applicationId, PLUS_MONTHLY_PRODUCT_ID),
        )
    }

    @Test
    fun `supporter uri opens the product-specific management page`() {
        assertEquals(
            "https://play.google.com/store/account/subscriptions" +
                "?sku=supporter_monthly&package=com.kungsbackacarcommunity.app",
            SubscriptionManagementLink.webUri(applicationId, SUPPORTER_MONTHLY_PRODUCT_ID),
        )
    }

    @Test
    fun `unknown product opens the generic subscriptions center`() {
        assertEquals(
            "https://play.google.com/store/account/subscriptions",
            SubscriptionManagementLink.webUri(applicationId, null),
        )
    }
}
