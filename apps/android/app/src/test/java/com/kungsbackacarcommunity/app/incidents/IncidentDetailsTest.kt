package com.kungsbackacarcommunity.app.incidents

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the detail sheet's decisions: which action a viewer is offered, and how
 * old a report reads as.
 *
 * The ownership rules are the part that must not be verified by looking at a
 * screen — offering "remove" on someone else's report, or "still there?" on your
 * own, are both wrong in ways that only show up when the backend rejects the
 * call.
 */
class IncidentDetailsTest {

    private val now = Instant.parse("2026-07-19T12:00:00Z")
    private val nowMillis = now.toEpochMilli()

    private fun incident(
        id: String = "i1",
        type: IncidentType = IncidentType.ACCIDENT,
        source: String = "user",
        reporterUid: String? = "reporter",
        createdAtIso: String? = null,
    ) = Incident(
        id = id,
        type = type,
        longitude = 12.0,
        latitude = 57.0,
        source = source,
        reporterUid = reporterUid,
        createdAtIso = createdAtIso,
    )

    // ---- ownership ---------------------------------------------------------

    @Test
    fun `my own report offers remove, never confirm`() {
        val mine = incident(reporterUid = "me")
        assertTrue(IncidentDetails.isOwnReport(mine, "me"))
        assertEquals(IncidentAction.Remove, IncidentDetails.actionFor(mine, "me"))
    }

    @Test
    fun `someone else's report offers confirm, never remove`() {
        val theirs = incident(reporterUid = "someone-else")
        assertFalse(IncidentDetails.isOwnReport(theirs, "me"))
        assertEquals(IncidentAction.Confirm, IncidentDetails.actionFor(theirs, "me"))
    }

    @Test
    fun `an imported Trafikverket row offers neither action`() {
        // `incidents-remove` rejects non-user sources for EVERYONE (admins
        // included), and confirming an authority's own feed is meaningless — so
        // the sheet must be informational only, whoever is looking at it.
        val imported =
            incident(source = INCIDENT_SOURCE_TRAFIKVERKET, reporterUid = null)
        assertEquals(IncidentOrigin.Trafikverket, IncidentDetails.originOf(imported))
        assertEquals(IncidentAction.None, IncidentDetails.actionFor(imported, "me"))
        assertEquals(IncidentAction.None, IncidentDetails.actionFor(imported, null))
    }

    @Test
    fun `an imported row is not ownable even if it somehow carries a reporter`() {
        // Belt-and-braces: source wins over reporterUid, so a malformed imported
        // doc cannot hand anyone a remove action the backend would then reject.
        val odd =
            incident(source = INCIDENT_SOURCE_TRAFIKVERKET, reporterUid = "me")
        assertEquals(IncidentAction.None, IncidentDetails.actionFor(odd, "me"))
    }

    @Test
    fun `an unidentified viewer never owns anything`() {
        val anonymous = incident(reporterUid = "reporter")
        assertFalse(IncidentDetails.isOwnReport(anonymous, null))
        assertFalse(IncidentDetails.isOwnReport(anonymous, ""))
        assertFalse(IncidentDetails.isOwnReport(anonymous, "   "))
        // ...and so is offered confirm, not remove.
        assertEquals(IncidentAction.Confirm, IncidentDetails.actionFor(anonymous, null))
    }

    @Test
    fun `a report with no reporter is never owned`() {
        // Two absent uids must not compare equal into an ownership match.
        val orphan = incident(reporterUid = null)
        assertFalse(IncidentDetails.isOwnReport(orphan, null))
        assertFalse(IncidentDetails.isOwnReport(orphan, "me"))
        assertFalse(IncidentDetails.isOwnReport(incident(reporterUid = ""), ""))
    }

    @Test
    fun `every incident type resolves to an action for both viewers`() {
        // Exhaustive over the enum: a new category must not fall into some
        // unhandled state on the sheet either.
        for (type in IncidentType.entries) {
            assertEquals(
                IncidentAction.Remove,
                IncidentDetails.actionFor(incident(type = type, reporterUid = "me"), "me"),
            )
            assertEquals(
                IncidentAction.Confirm,
                IncidentDetails.actionFor(incident(type = type, reporterUid = "you"), "me"),
            )
        }
    }

    // ---- confirm availability ---------------------------------------------

    @Test
    fun `confirming is wired to the incidents-confirm backend`() {
        // The `incidents-confirm` callable exists and is deployed, so the sheet
        // renders the "still there?" action live rather than disabled. This guards
        // against a regression back to the disabled placeholder.
        assertEquals(ConfirmAvailability.Wired, IncidentDetails.confirmAvailability)
    }

    // ---- age ---------------------------------------------------------------

    @Test
    fun `a fresh report reads as just now`() {
        assertEquals(IncidentAge.JustNow, IncidentDetails.ageOf(now.toString(), nowMillis))
        assertEquals(
            IncidentAge.JustNow,
            IncidentDetails.ageOf(now.minusSeconds(59).toString(), nowMillis),
        )
    }

    @Test
    fun `minutes, hours and days each get their own bucket`() {
        assertEquals(
            IncidentAge.Minutes(1),
            IncidentDetails.ageOf(now.minusSeconds(60).toString(), nowMillis),
        )
        assertEquals(
            IncidentAge.Minutes(59),
            IncidentDetails.ageOf(now.minus(Duration.ofMinutes(59)).toString(), nowMillis),
        )
        assertEquals(
            IncidentAge.Hours(1),
            IncidentDetails.ageOf(now.minus(Duration.ofMinutes(60)).toString(), nowMillis),
        )
        assertEquals(
            IncidentAge.Hours(23),
            IncidentDetails.ageOf(now.minus(Duration.ofHours(23)).toString(), nowMillis),
        )
        assertEquals(
            IncidentAge.Days(1),
            IncidentDetails.ageOf(now.minus(Duration.ofHours(24)).toString(), nowMillis),
        )
        assertEquals(
            IncidentAge.Days(3),
            IncidentDetails.ageOf(now.minus(Duration.ofDays(3)).toString(), nowMillis),
        )
    }

    @Test
    fun `ages round down, never up`() {
        // 90 seconds is "1 min ago", not "2 min ago": the sheet must never claim
        // an incident is staler than it actually is.
        assertEquals(
            IncidentAge.Minutes(1),
            IncidentDetails.ageOf(now.minusSeconds(119).toString(), nowMillis),
        )
    }

    @Test
    fun `a missing or unparseable timestamp reads as unknown, not as fresh`() {
        // The backend legitimately sends a null createdAt for a report read back
        // in the same round-trip as its own write. Guessing "just now" there
        // would be a lie on every row with a bad timestamp too.
        assertEquals(IncidentAge.Unknown, IncidentDetails.ageOf(null, nowMillis))
        assertEquals(IncidentAge.Unknown, IncidentDetails.ageOf("", nowMillis))
        assertEquals(IncidentAge.Unknown, IncidentDetails.ageOf("   ", nowMillis))
        assertEquals(IncidentAge.Unknown, IncidentDetails.ageOf("yesterday", nowMillis))
        assertEquals(IncidentAge.Unknown, IncidentDetails.ageOf("2026-07-19", nowMillis))
    }

    @Test
    fun `a future timestamp degrades to just now rather than a negative age`() {
        // Device/server clock skew, not a bug in the data.
        assertEquals(
            IncidentAge.JustNow,
            IncidentDetails.ageOf(now.plus(Duration.ofMinutes(5)).toString(), nowMillis),
        )
    }

    @Test
    fun `the incident overload reads the timestamp off the model`() {
        val reported = incident(createdAtIso = now.minus(Duration.ofMinutes(20)).toString())
        assertEquals(IncidentAge.Minutes(20), IncidentDetails.ageOf(reported, nowMillis))
    }

    // ---- clear-vote eligibility -------------------------------------------
    //
    // "Can this member take this hazard off everyone else's map?" is exactly the
    // decision that must not be verified by squinting at a screen.

    /** The incident sits at 57.0 N, 12.0 E; this walks north from it. */
    private fun northOfIncident(meters: Double) = IncidentPoint(57.0 + meters / 111_320.0, 12.0)

    @Test
    fun `a member standing at the incident may vote it gone`() {
        assertEquals(
            ClearVoteEligibility.Available,
            IncidentDetails.clearVoteEligibility(incident(), IncidentPoint(57.0, 12.0)),
        )
    }

    @Test
    fun `a member just inside the radius may vote, just outside may not`() {
        val radius = IncidentDetails.CLEAR_VOTE_RADIUS_METERS
        assertEquals(
            ClearVoteEligibility.Available,
            IncidentDetails.clearVoteEligibility(incident(), northOfIncident(radius - 20)),
        )
        assertEquals(
            ClearVoteEligibility.TooFar,
            IncidentDetails.clearVoteEligibility(incident(), northOfIncident(radius + 20)),
        )
    }

    @Test
    fun `a member across town is told to drive closer, not shown a dead button`() {
        assertEquals(
            ClearVoteEligibility.TooFar,
            IncidentDetails.clearVoteEligibility(incident(), northOfIncident(5_000.0)),
        )
    }

    @Test
    fun `no location is its own reason, never mistaken for being in range`() {
        // "Turn on location" and "drive closer" are different instructions, and
        // sending someone with location switched off on a drive would be a lie.
        assertEquals(
            ClearVoteEligibility.NoLocation,
            IncidentDetails.clearVoteEligibility(incident(), null),
        )
    }

    @Test
    fun `an imported Trafikverket row can never be voted gone, even from on top of it`() {
        // The importer full-overwrites it every 30 minutes, so the vote would be
        // erased; the backend rejects it for everyone including admins. The
        // source check therefore has to come FIRST, before proximity.
        assertEquals(
            ClearVoteEligibility.ImportedSource,
            IncidentDetails.clearVoteEligibility(
                incident(source = INCIDENT_SOURCE_TRAFIKVERKET, reporterUid = null),
                IncidentPoint(57.0, 12.0),
            ),
        )
        // ...and still, with no location at all, for the same reason.
        assertEquals(
            ClearVoteEligibility.ImportedSource,
            IncidentDetails.clearVoteEligibility(
                incident(source = INCIDENT_SOURCE_TRAFIKVERKET, reporterUid = null),
                null,
            ),
        )
    }

    @Test
    fun `a garbled coordinate fails to the FAR side, never to available`() {
        // NaN fails every comparison, so a naive distance check would let a
        // garbled position through as "close enough". Failing far is the
        // direction that cannot take a live hazard off the map.
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(
                ClearVoteEligibility.TooFar,
                IncidentDetails.clearVoteEligibility(incident(), IncidentPoint(bad, 12.0)),
            )
            assertEquals(
                ClearVoteEligibility.TooFar,
                IncidentDetails.clearVoteEligibility(incident(), IncidentPoint(57.0, bad)),
            )
        }
    }

    @Test
    fun `the distance helper agrees with known great-circle distances`() {
        // Sanity-check the haversine itself, so the eligibility tests above are
        // measuring the right thing. Kungsbacka to Gothenburg is ~25 km.
        val km = IncidentDetails.distanceMeters(57.4874, 12.0757, 57.7089, 11.9746) / 1000.0
        assertTrue("expected ~25 km, got %.1f km".format(km), km > 23 && km < 27)
        assertEquals(0.0, IncidentDetails.distanceMeters(57.0, 12.0, 57.0, 12.0), 0.001)
    }

    @Test
    fun `the client radius matches the radius the backend enforces`() {
        // Mirrored ONLY to avoid a wasted round-trip; the server re-computes and
        // decides. Pinned so a drift is a deliberate edit rather than a surprise.
        assertEquals(300.0, IncidentDetails.CLEAR_VOTE_RADIUS_METERS, 0.0)
    }
}
