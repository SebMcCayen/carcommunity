package com.kungsbackacarcommunity.app.privacy

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerStatsConsentTest {
    private val consent = mapOf<String, Any?>(
        "anonymousPartnerStatsOptIn" to true,
        "anonymousPartnerStatsConsentVersion" to 1L,
        "anonymousPartnerStatsConsentAt" to Timestamp(1_700_000_000, 0),
    )

    @Test
    fun `only affirmative current version with a valid timestamp enables analytics`() {
        assertEquals(PartnerStatsConsentState.Chosen(true), partnerStatsConsentState(consent))
        assertTrue(partnerStatsConsentState(consent).allowsOptionalAnalytics())
        assertEquals(PartnerStatsConsentState.Chosen(false), partnerStatsConsentState(consent + ("anonymousPartnerStatsOptIn" to false)))
    }

    @Test
    fun `missing legacy and unsupported versions all default off without migration`() {
        val documents = listOf(
            emptyMap(),
            mapOf("anonymousPartnerStatsOptIn" to true),
            consent - "anonymousPartnerStatsConsentVersion",
            consent - "anonymousPartnerStatsConsentAt",
            consent + ("anonymousPartnerStatsConsentVersion" to 2L),
        )
        documents.forEach { document ->
            assertEquals(document.toString(), PartnerStatsConsentState.DefaultOff, partnerStatsConsentState(document))
            assertFalse(partnerStatsConsentState(document).allowsOptionalAnalytics())
        }
        assertEquals(true, consent["anonymousPartnerStatsOptIn"])
    }

    @Test
    fun `present malformed evidence locks saving even for a stored decline`() {
        val malformedEvidence = listOf(
            "anonymousPartnerStatsConsentVersion" to "1",
            "anonymousPartnerStatsConsentVersion" to true,
            "anonymousPartnerStatsConsentVersion" to null,
            "anonymousPartnerStatsConsentVersion" to 1.5,
            "anonymousPartnerStatsConsentVersion" to Double.NaN,
            "anonymousPartnerStatsConsentAt" to "2026-11-20",
            "anonymousPartnerStatsConsentAt" to null,
            "anonymousPartnerStatsConsentAt" to Timestamp(0, 0),
            "anonymousPartnerStatsConsentAt" to Timestamp(-1, 0),
        )
        listOf(false, true).forEach { choice ->
            malformedEvidence.forEach { malformed ->
                assertEquals(PartnerStatsConsentState.Unknown, partnerStatsConsentState(consent + ("anonymousPartnerStatsOptIn" to choice) + malformed))
            }
        }
        listOf(null, "true", 1).forEach { malformed ->
            assertEquals(PartnerStatsConsentState.Unknown, partnerStatsConsentState(consent + ("anonymousPartnerStatsOptIn" to malformed)))
        }
        assertEquals(PartnerStatsConsentState.Chosen(true), partnerStatsConsentState(consent + ("anonymousPartnerStatsConsentVersion" to 1.0)))
    }

    @Test
    fun `cache pending writes and unresolved snapshot cannot grant or seed a save`() {
        assertEquals(PartnerStatsConsentState.Unknown, partnerStatsConsentState(null))
        assertEquals(PartnerStatsConsentState.Unknown, partnerStatsConsentState(consent, fromCache = true))
        assertEquals(PartnerStatsConsentState.Unknown, partnerStatsConsentState(consent, hasPendingWrites = true))
        assertFalse(PartnerStatsConsentState.Unknown.allowsOptionalAnalytics())
        assertFalse(PartnerStatsConsentState.DefaultOff.allowsOptionalAnalytics())
        assertFalse(PartnerStatsConsentState.Chosen(false).allowsOptionalAnalytics())
    }
}
