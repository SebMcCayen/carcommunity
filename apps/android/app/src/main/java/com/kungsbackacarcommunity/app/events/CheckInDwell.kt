package com.kungsbackacarcommunity.app.events

/**
 * DWELL COUNTDOWN — pure domain logic for the check-in progress bar (no Firebase
 * / Android types) so the remaining-time, the progress fraction and the
 * "can I confirm yet?" decision are JVM-unit-testable and shared by the
 * coordinator and the screen.
 *
 * WHAT THE COUNTDOWN MEANS. Attendance is proven by being measurably present
 * TWICE, at least ten minutes apart, each time inside the event's geofence — see
 * functions/src/events/checkIn.ts and evaluateAttendance in
 * points-economy-core.ts. The client cannot decide attendance (the server does,
 * against server-validated samples), but it CAN tell the member how long they
 * still have to wait before that second, verifying check-in is worth attempting:
 * ten minutes from the FIRST recorded in-geofence fix. This object is that
 * countdown, nothing more.
 *
 * FIRST-AND-LAST, NOT CONTINUOUS. The sanctioned model needs the member inside
 * the fence at the FIRST fix and again at the FINAL (>= 10-min-later) fix — not
 * continuously in between. A member who walks to a shop and back mid-dwell still
 * qualifies. So the countdown is driven purely by wall-clock time since the
 * first sample and is NEVER reset by a temporary exit; only the final fix has to
 * be inside the fence, which [canCompleteNow] and [firstAndLastInside] encode.
 */
object CheckInDwell {
    /**
     * How long the member must dwell before a verifying check-in can succeed —
     * mirrors REQUIRED_DWELL_MS in functions/src/points/points-economy-core.ts
     * (10 minutes) and MIN_SAMPLE_SPACING_MS (the required span between the
     * first and last qualifying sample, also 10 minutes). The server is the
     * authority; this only decides when the "confirm" affordance lights up, so
     * it must not be SHORTER than the server or the UI would invite a check-in
     * the server still rejects.
     */
    const val REQUIRED_DWELL_MS = 10L * 60_000L

    /** Dwell elapsed since the first sample, clamped to [0, REQUIRED_DWELL_MS]. */
    fun elapsedMillis(firstSampleAtMillis: Long, nowMillis: Long): Long =
        (nowMillis - firstSampleAtMillis).coerceIn(0L, REQUIRED_DWELL_MS)

    /**
     * Time still remaining before the member can confirm, in millis, clamped to
     * [0, REQUIRED_DWELL_MS]. Zero once the dwell is complete.
     */
    fun remainingMillis(firstSampleAtMillis: Long, nowMillis: Long): Long =
        REQUIRED_DWELL_MS - elapsedMillis(firstSampleAtMillis, nowMillis)

    /** Progress of the dwell as a 0f..1f fraction, for a determinate progress bar. */
    fun progressFraction(firstSampleAtMillis: Long, nowMillis: Long): Float =
        if (REQUIRED_DWELL_MS <= 0L) {
            1f
        } else {
            (elapsedMillis(firstSampleAtMillis, nowMillis).toFloat() / REQUIRED_DWELL_MS)
                .coerceIn(0f, 1f)
        }

    /** True once at least [REQUIRED_DWELL_MS] has passed since the first sample. */
    fun isDwellElapsed(firstSampleAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - firstSampleAtMillis >= REQUIRED_DWELL_MS

    /**
     * Whether a verifying check-in can succeed right now: the dwell has elapsed
     * AND the latest fix is inside the fence. A member who left the area still
     * has to come BACK for the final fix — the timer keeping running is not the
     * same as being allowed to confirm from the next town.
     */
    fun canCompleteNow(
        firstSampleAtMillis: Long,
        nowMillis: Long,
        latestFixInsideFence: Boolean,
    ): Boolean = isDwellElapsed(firstSampleAtMillis, nowMillis) && latestFixInsideFence

    /**
     * The "first-and-last inside, tolerate the middle" predicate over a series
     * of in-fence flags (one per fix, in capture order): attendance needs the
     * FIRST fix and the FINAL fix inside the fence, and does not care whether any
     * fix in between was. Needs at least two fixes — a single ping can never
     * prove a ten-minute stay. Mirrors, on the client, exactly what
     * evaluateAttendance rewards on the server without weakening it: a `false`
     * here only ever HIDES the confirm affordance; the server still decides.
     */
    fun firstAndLastInside(insideFlags: List<Boolean>): Boolean =
        insideFlags.size >= 2 && insideFlags.first() && insideFlags.last()

    /**
     * The remaining time split into whole minutes and seconds for a "m:ss"
     * label, rounding UP so a 0:00 is only ever shown at genuinely-zero
     * remaining (a 1 ms remainder still reads as 0:01, never a premature 0:00).
     */
    fun remainingMinutesSeconds(remainingMillis: Long): Pair<Int, Int> {
        val totalSeconds = (remainingMillis.coerceAtLeast(0L) + 999L) / 1000L
        return (totalSeconds / 60L).toInt() to (totalSeconds % 60L).toInt()
    }
}
