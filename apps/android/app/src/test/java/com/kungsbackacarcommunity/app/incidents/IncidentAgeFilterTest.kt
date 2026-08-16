package com.kungsbackacarcommunity.app.incidents

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure Trafikverket alert max-age filter decision: given an incident's
 * `postedAt`, its source, `now`, and the selected option, is it drawn?
 *
 * This is the whole behaviour of the feature — the SharedPreferences store and the
 * layers-popup slider are thin shells over it — so the scope rules (Trafikverket
 * only, member reports always shown, missing posted-time shown, "All" = no limit)
 * and the inclusive age boundary are pinned here, off-device.
 */
class IncidentAgeFilterTest {

    private val now = Instant.parse("2026-08-04T12:00:00Z")
    private val nowMillis = now.toEpochMilli()

    private fun incident(
        source: String = INCIDENT_SOURCE_TRAFIKVERKET,
        postedAtIso: String? = null,
    ) = Incident(
        id = "i1",
        type = IncidentType.ROADWORK,
        longitude = 12.0,
        latitude = 57.0,
        source = source,
        postedAtIso = postedAtIso,
    )

    /** An ISO instant [ageMillis] before [now]. */
    private fun postedAgo(ageMillis: Long): String =
        now.minusMillis(ageMillis).toString()

    @Test
    fun `unset default is show everything`() {
        assertEquals(IncidentAgeOption.ALL, IncidentAgeFilter.DEFAULT)
    }

    @Test
    fun `All shows a Trafikverket alert of any age`() {
        val ancient = incident(postedAtIso = postedAgo(3650L * 24 * 60 * 60 * 1000))
        assertTrue(IncidentAgeFilter.isVisible(ancient, nowMillis, IncidentAgeOption.ALL))
    }

    @Test
    fun `a fresh Trafikverket alert is shown under a strict limit`() {
        val fresh = incident(postedAtIso = postedAgo(60 * 60 * 1000)) // 1h old
        assertTrue(IncidentAgeFilter.isVisible(fresh, nowMillis, IncidentAgeOption.HOURS_6))
    }

    @Test
    fun `a Trafikverket alert older than the limit is hidden`() {
        val old = incident(postedAtIso = postedAgo(2L * 24 * 60 * 60 * 1000)) // 2 days
        assertFalse(IncidentAgeFilter.isVisible(old, nowMillis, IncidentAgeOption.DAY_1))
    }

    @Test
    fun `the age boundary is inclusive - exactly at the limit is still shown`() {
        val limit = IncidentAgeOption.DAY_1.maxAgeMillis!!
        val exactly = incident(postedAtIso = postedAgo(limit))
        assertTrue(IncidentAgeFilter.isVisible(exactly, nowMillis, IncidentAgeOption.DAY_1))
        // One millisecond past the limit flips to hidden.
        val justOver = incident(postedAtIso = postedAgo(limit + 1))
        assertFalse(IncidentAgeFilter.isVisible(justOver, nowMillis, IncidentAgeOption.DAY_1))
    }

    @Test
    fun `a member report is never age-filtered`() {
        // Even with no posted-time and a strict limit, a user's own report shows.
        val member = incident(source = "user", postedAtIso = null)
        assertTrue(IncidentAgeFilter.isVisible(member, nowMillis, IncidentAgeOption.HOURS_6))
        // And an OLD member report (aged by createdAt in the sheet) is still not
        // hidden by this filter, which only aims at Trafikverket's backlog.
        val oldMember = incident(source = "user", postedAtIso = postedAgo(365L * 24 * 60 * 60 * 1000))
        assertTrue(IncidentAgeFilter.isVisible(oldMember, nowMillis, IncidentAgeOption.HOURS_6))
    }

    @Test
    fun `a Trafikverket alert with missing posted-time is shown`() {
        val noTime = incident(postedAtIso = null)
        assertTrue(IncidentAgeFilter.isVisible(noTime, nowMillis, IncidentAgeOption.HOURS_6))
    }

    @Test
    fun `a Trafikverket alert with an unparseable posted-time is shown`() {
        val garbage = incident(postedAtIso = "not-a-timestamp")
        assertTrue(IncidentAgeFilter.isVisible(garbage, nowMillis, IncidentAgeOption.HOURS_6))
    }

    @Test
    fun `a future posted-time (clock skew) is treated as brand new`() {
        val future = incident(postedAtIso = now.plusMillis(60 * 60 * 1000).toString())
        assertTrue(IncidentAgeFilter.isVisible(future, nowMillis, IncidentAgeOption.HOURS_6))
    }

    @Test
    fun `visible() drops only the aged-out Trafikverket rows`() {
        val fresh = incident(postedAtIso = postedAgo(60 * 60 * 1000)) // 1h
        val stale = incident(postedAtIso = postedAgo(3L * 24 * 60 * 60 * 1000)) // 3 days
        val noTime = incident(postedAtIso = null)
        val member = incident(source = "user")
        val result =
            IncidentAgeFilter.visible(
                listOf(fresh, stale, noTime, member),
                nowMillis,
                IncidentAgeOption.DAY_1,
            )
        assertEquals(listOf(fresh, noTime, member), result)
    }

    @Test
    fun `visible() with All returns the same list instance (no work)`() {
        val list = listOf(incident(postedAtIso = postedAgo(999L * 24 * 60 * 60 * 1000)))
        assertSame(list, IncidentAgeFilter.visible(list, nowMillis, IncidentAgeOption.ALL))
    }

    @Test
    fun `fromStoredName maps unset and unrecognised values to the default`() {
        assertEquals(IncidentAgeFilter.DEFAULT, IncidentAgeFilter.fromStoredName(null))
        assertEquals(IncidentAgeFilter.DEFAULT, IncidentAgeFilter.fromStoredName(""))
        assertEquals(IncidentAgeFilter.DEFAULT, IncidentAgeFilter.fromStoredName("NOT_AN_OPTION"))
        // An ordinal-as-string is not a name, so it must NOT be reinterpreted.
        assertEquals(IncidentAgeFilter.DEFAULT, IncidentAgeFilter.fromStoredName("0"))
    }

    @Test
    fun `a stored name round-trips back to its option`() {
        for (option in IncidentAgeOption.entries) {
            assertEquals(option, IncidentAgeFilter.fromStoredName(option.name))
        }
    }

    @Test
    fun `slider step count is the ticks between the option endpoints`() {
        assertEquals(IncidentAgeOption.entries.size - 2, IncidentAgeFilter.sliderSteps)
        assertEquals(IncidentAgeOption.entries.toList(), IncidentAgeFilter.orderedOptions)
    }

    @Test
    fun `optionForSliderIndex resolves each whole notch to its option`() {
        val options = IncidentAgeFilter.orderedOptions
        for (i in options.indices) {
            assertEquals(options[i], IncidentAgeFilter.optionForSliderIndex(i.toFloat()))
        }
    }

    @Test
    fun `optionForSliderIndex rounds a mid-drag fractional value to the nearest notch`() {
        // The whole point of issue #871: a partial drag must resolve to a real option
        // so the live label can move as the thumb does. Rounds to nearest.
        val options = IncidentAgeFilter.orderedOptions
        assertEquals(options[0], IncidentAgeFilter.optionForSliderIndex(0.49f))
        assertEquals(options[1], IncidentAgeFilter.optionForSliderIndex(0.5f))
        assertEquals(options[2], IncidentAgeFilter.optionForSliderIndex(1.8f))
    }

    @Test
    fun `optionForSliderIndex clamps an out-of-range value to the ends`() {
        val options = IncidentAgeFilter.orderedOptions
        assertEquals(options.first(), IncidentAgeFilter.optionForSliderIndex(-3f))
        assertEquals(options.last(), IncidentAgeFilter.optionForSliderIndex(999f))
    }
}
