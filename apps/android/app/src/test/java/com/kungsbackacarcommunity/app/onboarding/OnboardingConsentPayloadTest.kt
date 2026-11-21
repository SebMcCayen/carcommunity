package com.kungsbackacarcommunity.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OnboardingConsentPayloadTest {
    @Test
    fun `hidden optional choice sends no consent evidence`() {
        val payload = onboardingConsentPayload("Member", null)
        assertFalse(payload.containsKey("anonymousPartnerStatsOptIn"))
        assertFalse(payload.containsKey("anonymousPartnerStatsConsentVersion"))
        assertFalse(payload.containsKey("anonymousPartnerStatsConsentAt"))
    }

    @Test
    fun `included choice sends current version and leaves timestamp to server`() {
        listOf(false, true).forEach { choice ->
            val payload = onboardingConsentPayload("Member", choice)
            assertEquals(choice, payload["anonymousPartnerStatsOptIn"])
            assertEquals(1, payload["anonymousPartnerStatsConsentVersion"])
            assertFalse(payload.containsKey("anonymousPartnerStatsConsentAt"))
        }
    }
}
