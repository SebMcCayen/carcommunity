package com.kungsbackacarcommunity.app.incidents

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Pure, Android-free logic behind the incident detail sheet: how old a report
 * is, where it came from, and which of the two actions ("still there?" /
 * "remove my report") a given viewer is actually offered.
 *
 * Kept out of the composable so all of it is JVM-unit-testable without a device
 * or a Firebase project — the ownership rules in particular are the kind of
 * thing that must not be verified by squinting at a screen.
 */

/** How old a report is, bucketed for display. Rendered by the sheet as a string. */
sealed interface IncidentAge {
    /** Under a minute old. */
    data object JustNow : IncidentAge

    /** [minutes] is 1..59. */
    data class Minutes(val minutes: Int) : IncidentAge

    /** [hours] is 1..23. */
    data class Hours(val hours: Int) : IncidentAge

    /** [days] is 1 or more. */
    data class Days(val days: Int) : IncidentAge

    /**
     * No usable timestamp. Reached when the backend sent no `createdAt` (a
     * just-written report whose server timestamp has not resolved) or an
     * unparseable one. Shown honestly as "reported time unknown" rather than
     * guessed at — a wrong age on a stale-incident sheet is worse than no age.
     */
    data object Unknown : IncidentAge
}

/** Where an incident came from, as the sheet presents it. */
enum class IncidentOrigin {
    /** A crowd-sourced report from a community member. */
    Member,

    /** Imported from Trafikverket's open road-data feed. */
    Trafikverket,
}

/**
 * Whether the "still there?" confirmation can actually reach a backend.
 *
 * Mirrors the shape `MessageModeration.ReportAvailability` uses for the missing
 * report callables: the gap is named in the type system, so the day the callable
 * lands the fix is one line here and the UI follows.
 *
 * BACKEND GAP — what [BackendMissing] is waiting on:
 *
 * The incidents domain currently exposes only `incidents-report`,
 * `incidents-listNearby`, `incidents-remove`, `incidents-cleanupExpired` and
 * `incidents-syncTrafikverket`. There is NO confirm callable, so confirming is
 * rendered disabled with a plain explanation rather than pretending to work or
 * being faked client-side (a client-side "confirmed" that does not extend the
 * document's `expiresAt` would show a green tick on an incident that then
 * vanishes on the next sweep anyway).
 *
 * Wanted: `incidents-confirm` (europe-west1, auth + App Check), taking
 * `{ incidentId: string }` and returning
 * `{ confirmationCount: number, expiresAt: string, alreadyConfirmed: boolean }`.
 * Semantics:
 *  - one confirmation per user per incident — a repeat call is an idempotent
 *    no-op returning `alreadyConfirmed: true` and the unchanged count;
 *  - the REPORTER MAY NOT confirm their own report (`failed-precondition`);
 *    self-confirmation would make the expiry indefinitely extendable by one
 *    person, which is the exact failure the TTL exists to prevent;
 *  - a confirmation extends `expiresAt` (bounded — e.g. one further per-type
 *    TTL from now, capped at some absolute maximum so an incident cannot be
 *    kept alive forever) and increments a `confirmationCount` field;
 *  - imported (`source: 'trafikverket'`) incidents reject confirmation the same
 *    way `remove` does — their lifecycle belongs to the importer.
 * The returned `confirmationCount` and `expiresAt` let the sheet show "confirmed
 * by N" and the refreshed expiry without a second round-trip.
 */
enum class ConfirmAvailability {
    /** A confirm callable exists and is wired. */
    Wired,

    /** No confirm callable exists yet — the action renders disabled + explained. */
    BackendMissing,
}

/** The action the sheet offers for a given incident and viewer. */
enum class IncidentAction {
    /**
     * "Still there?" — offered on someone ELSE'S member report. Currently
     * rendered disabled (see [ConfirmAvailability]).
     */
    Confirm,

    /** "Remove my report" — offered on the viewer's OWN member report. */
    Remove,

    /**
     * Nothing actionable. An imported Trafikverket row: it cannot be removed
     * (`incidents-remove` rejects non-user sources for everyone, admins
     * included) and confirming someone else's authoritative feed is meaningless,
     * so the sheet is informational only.
     */
    None,
}

object IncidentDetails {
    /**
     * The confirm capability as it stands in this build. A single constant so
     * the UI, the tests, and the PR that lands the callable all agree on one
     * place to flip.
     */
    val confirmAvailability: ConfirmAvailability = ConfirmAvailability.BackendMissing

    /** Where [incident] came from. */
    fun originOf(incident: Incident): IncidentOrigin =
        if (incident.source == INCIDENT_SOURCE_TRAFIKVERKET) {
            IncidentOrigin.Trafikverket
        } else {
            IncidentOrigin.Member
        }

    /**
     * Whether [viewerUid] reported [incident].
     *
     * A null/blank [viewerUid] (signed out, or a uid we could not resolve) is
     * never an owner, and a null [Incident.reporterUid] (an imported row) is
     * never owned — so neither side's absence can be mistaken for a match and
     * hand a stranger the remove action.
     */
    fun isOwnReport(incident: Incident, viewerUid: String?): Boolean {
        val viewer = viewerUid?.takeIf { it.isNotBlank() } ?: return false
        val reporter = incident.reporterUid?.takeIf { it.isNotBlank() } ?: return false
        return viewer == reporter
    }

    /**
     * The single action to offer [viewerUid] for [incident].
     *
     * Deliberately one action, not a menu: your own report offers Remove and
     * never Confirm (confirming your own sighting proves nothing and the wanted
     * callable rejects it), someone else's member report offers Confirm and
     * never Remove (the backend would reject it), and an imported row offers
     * neither.
     */
    fun actionFor(incident: Incident, viewerUid: String?): IncidentAction =
        when {
            originOf(incident) == IncidentOrigin.Trafikverket -> IncidentAction.None
            isOwnReport(incident, viewerUid) -> IncidentAction.Remove
            else -> IncidentAction.Confirm
        }

    /**
     * How long ago [createdAtIso] was, relative to [nowMillis].
     *
     * Buckets rather than a precise duration, because that is how the value is
     * used ("is this fresh enough to trust?"). Truncating division is
     * intentional: 90 seconds is "1 min", not "2 min", so the sheet never claims
     * an incident is older than it is.
     *
     * A timestamp in the FUTURE (clock skew between the device and the server)
     * degrades to [IncidentAge.JustNow] rather than a negative age.
     */
    fun ageOf(createdAtIso: String?, nowMillis: Long): IncidentAge {
        val created = parseInstant(createdAtIso) ?: return IncidentAge.Unknown
        val elapsed = Duration.between(created, Instant.ofEpochMilli(nowMillis))
        if (elapsed.isNegative) return IncidentAge.JustNow
        val minutes = elapsed.toMinutes()
        return when {
            minutes < 1 -> IncidentAge.JustNow
            minutes < MINUTES_PER_HOUR -> IncidentAge.Minutes(minutes.toInt())
            minutes < MINUTES_PER_DAY -> IncidentAge.Hours((minutes / MINUTES_PER_HOUR).toInt())
            else -> IncidentAge.Days((minutes / MINUTES_PER_DAY).toInt())
        }
    }

    /** Convenience overload reading the timestamp straight off [incident]. */
    fun ageOf(incident: Incident, nowMillis: Long): IncidentAge =
        ageOf(incident.createdAtIso, nowMillis)

    /**
     * Parses the backend's ISO-8601 instant, returning null for anything
     * missing, blank, or malformed. Never throws: a bad timestamp downgrades the
     * sheet to "time unknown", it does not take the sheet down with it.
     */
    private fun parseInstant(value: String?): Instant? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private const val MINUTES_PER_HOUR = 60L
    private const val MINUTES_PER_DAY = 24L * 60L
}
