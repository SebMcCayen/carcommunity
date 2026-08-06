package com.kungsbackacarcommunity.app.events

/**
 * Geofenced event CHECK-IN — pure domain logic (no Firebase / Android types) so
 * the window gating and the server-result mapping are JVM-unit-testable and
 * shared by the coordinator, the repository and the screen.
 *
 * The proof that someone attended a meet is a VERIFIED check-in, not an RSVP:
 * the member taps "Check in" while physically at the event, the client sends a
 * one-shot GPS fix, and the backend (functions/src/events/checkIn.ts) decides —
 * server-side — whether the fix is inside the event's geofence (150 m) and its
 * time window, running the same anti-fraud pipeline a Kronjakt claim does. This
 * file only mirrors the WINDOW so the button appears at the right time and
 * never lies about what is possible; the geofence, the dwell and the anti-cheat
 * are the server's alone (a client cannot be trusted with any of them).
 */

/**
 * The caller's own attendance record (eventAttendance/{eventId}__{uid}), read
 * off the owner-readable teaser fields only. [verified] is the proof-complete
 * flag; [checkedIn] is true once any sample has been recorded, so the UI can
 * show a pending state between the first check-in and verification.
 */
data class EventAttendanceStatus(
    val verified: Boolean,
    val sampleCount: Int,
) {
    val checkedIn: Boolean get() = verified || sampleCount > 0
}

/** A one-shot position fix for a check-in — the client's whole contribution. */
data class CheckInFix(
    val latitude: Double,
    val longitude: Double,
    /** GPS accuracy in metres, when the fix reported one. */
    val accuracyMeters: Double?,
    /** The FIX's own timestamp (Location.time), not "now" — the server checks freshness against it. */
    val capturedAtMillis: Long,
    /** Location.isMock, reported truthfully — the server penalises `true` on its own. */
    val isMock: Boolean,
)

/**
 * The server's check-in verdict for one sample (functions/src/events/checkIn.ts
 * CheckInResult), plus [UNKNOWN] for an unrecognised/absent value so a garbled
 * response is never silently read as success.
 */
enum class CheckInResult(val wire: String) {
    /** Recorded, but attendance not yet complete (needs the geofence + dwell evidence). */
    RECORDED("recorded"),

    /** Attendance verified by this sample — the proof is complete. */
    VERIFIED("verified"),

    /** Already verified on an earlier check-in; nothing further to do. */
    ALREADY_VERIFIED("already_verified"),

    /** Too far from the event's location. */
    OUTSIDE_GEOFENCE("outside_geofence"),

    /** Outside the event's check-in window. */
    OUTSIDE_WINDOW("outside_window"),

    /** The fix was too old to trust. */
    POSITION_TOO_OLD("position_too_old"),

    /** The sample tripped the anti-fraud pipeline (mock/spoof/implausible). */
    RISK_REVIEW("risk_review"),

    /** The event is not open for check-in (draft/cancelled/missing coordinates). */
    EVENT_NOT_CHECKINABLE("event_not_checkinable"),

    /** Unrecognised or absent — treated as a transient failure, never as success. */
    UNKNOWN("unknown"),
    ;

    /** Whether this result means the member's attendance is now proven. */
    val isVerified: Boolean
        get() = this == VERIFIED || this == ALREADY_VERIFIED

    companion object {
        fun fromWire(value: String?): CheckInResult =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

object EventCheckIn {
    /**
     * How long BEFORE an event's start check-in opens — mirrors
     * ATTENDANCE_WINDOW_BEFORE_MS in functions/src/points/points-economy-core.ts
     * (30 min). The server is the authority; this only decides when the button
     * is offered, so it must not be looser than the server or the button would
     * promise a check-in the server will reject.
     */
    const val WINDOW_BEFORE_MS = 30L * 60_000L

    /** How long AFTER an event's effective end check-in stays open — ATTENDANCE_WINDOW_AFTER_MS (30 min). */
    const val WINDOW_AFTER_MS = 30L * 60_000L

    /**
     * Assumed duration when an event has a start but no explicit end — mirrors
     * DEFAULT_EVENT_DURATION_MS (4 h). The server applies the same default, so
     * the client window matches for a start-only event too.
     */
    const val DEFAULT_DURATION_MS = 4L * 60L * 60_000L

    /**
     * The [start, end] of an event's check-in window in epoch millis, or null
     * when the event has no readable start (the button cannot be time-gated, so
     * it is not offered). End is the explicit end, else start + [DEFAULT_DURATION_MS];
     * the window is padded by [WINDOW_BEFORE_MS] / [WINDOW_AFTER_MS] on each side.
     */
    fun window(event: EventSummary): LongRange? {
        val start = event.startsAtMillis ?: return null
        val end = event.endsAtMillis ?: (start + DEFAULT_DURATION_MS)
        return (start - WINDOW_BEFORE_MS)..(end + WINDOW_AFTER_MS)
    }

    /** Whether [nowMillis] falls inside the event's check-in window. */
    fun isWindowOpen(event: EventSummary, nowMillis: Long): Boolean =
        window(event)?.contains(nowMillis) == true

    /**
     * Whether the "Check in" action should be offered at all: the caller passes
     * the member gate, the event is checkinable and positioned (a check-in with
     * no event coordinates can never verify), and the window is open right now.
     *
     * PUBLISHED **or** COMPLETED — deliberately matching the server
     * (functions/src/events/checkIn.ts loadEventLocation, which accepts both):
     * the hourly auto-close sweep (or a creator/admin) can flip an event to
     * `completed` while members are still standing in the car park, and one of
     * them still inside the time window must be able to prove they were there. A
     * cancelled or draft event is never checkinable, and neither is one with no pin.
     */
    fun canCheckIn(
        passesMemberGate: Boolean,
        event: EventSummary,
        nowMillis: Long,
    ): Boolean =
        passesMemberGate &&
            (event.status == EventStatus.PUBLISHED || event.status == EventStatus.COMPLETED) &&
            event.latitude != null &&
            event.longitude != null &&
            isWindowOpen(event, nowMillis)

    /**
     * The moment the window's state next changes for [event] relative to
     * [nowMillis]: its opening edge if check-in has not opened yet, its closing
     * edge if it is open now, or null once it has closed (nothing left to
     * schedule) or the event has no readable time. Lets the screen re-evaluate
     * the button exactly once at the boundary instead of polling.
     */
    fun nextWindowBoundaryMillis(event: EventSummary, nowMillis: Long): Long? {
        val window = window(event) ?: return null
        return when {
            nowMillis < window.first -> window.first
            nowMillis <= window.last -> window.last
            else -> null
        }
    }

    /**
     * Builds the `events-checkIn` callable payload from a fix. The schema is
     * `.strict()` so only known keys are sent; `accuracyMeters` is omitted when
     * absent, and `isMockLocation` is ALWAYS sent (reported truthfully — the
     * server treats it as a one-way signal). `capturedAt` is the fix's own
     * timestamp serialised to ISO-8601 UTC, which the server freshness-checks.
     */
    fun checkInPayload(eventId: String, fix: CheckInFix): Map<String, Any> {
        val payload =
            mutableMapOf<String, Any>(
                "eventId" to eventId,
                "latitude" to fix.latitude,
                "longitude" to fix.longitude,
                "capturedAt" to Events.toIsoUtc(fix.capturedAtMillis),
                "isMockLocation" to fix.isMock,
            )
        fix.accuracyMeters?.let { payload["accuracyMeters"] = it }
        return payload
    }
}
