package com.kungsbackacarcommunity.app.billboards

import org.junit.Assert.assertEquals
import org.junit.Test

class BillboardTest {

    @Test
    fun `interaction types expose the backend wire values`() {
        assertEquals("impression", BillboardInteractionType.IMPRESSION.wire)
        assertEquals("open", BillboardInteractionType.OPEN.wire)
        assertEquals("navigate", BillboardInteractionType.NAVIGATE.wire)
        assertEquals("phone", BillboardInteractionType.PHONE.wire)
        assertEquals("website", BillboardInteractionType.WEBSITE.wire)
        assertEquals("offer_view", BillboardInteractionType.OFFER_VIEW.wire)
        assertEquals(6, BillboardInteractionType.values().size)
    }

    @Test
    fun `active billboards query limit is one hundred fifty`() {
        assertEquals(150L, Billboards.ACTIVE_BILLBOARDS_QUERY_LIMIT)
    }
}
