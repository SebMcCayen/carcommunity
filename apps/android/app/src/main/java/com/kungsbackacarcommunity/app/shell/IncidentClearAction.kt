package com.kungsbackacarcommunity.app.shell

import android.content.Context
import com.kungsbackacarcommunity.app.crownhunt.CrownLocation
import com.kungsbackacarcommunity.app.incidents.IncidentClearFix
import java.time.Instant

/**
 * Takes the position a clear vote is made from: a FRESH, high-accuracy fix, right
 * now.
 *
 * Reuses [CrownLocation.currentFix] rather than the cheaper
 * `CurrentLocation.lastKnown`, and the difference is the whole point. A route
 * origin is happy with a cached fix that may be minutes old; a clear vote is
 * evidence that this member is looking at the spot RIGHT NOW, and the backend
 * refuses a stale one. `CrownLocation` already asks for exactly that — a current
 * high-accuracy fix carrying its own timestamp, its accuracy, and `isMock` —
 * because a Kronjakt claim needs the same thing, so this is one position source
 * rather than a second near-copy of it.
 *
 * [IncidentClearFix.capturedAtIso] is the FIX's own timestamp, never "now":
 * stamping it with the moment we happened to read it would hide precisely the
 * staleness the server checks for.
 *
 * Null when no fix is available (permission off, no GPS, config-less build), in
 * which case nothing is sent — a vote without a position is not a weaker vote,
 * it is not a vote.
 */
suspend fun currentIncidentClearFix(context: Context): IncidentClearFix? {
    val fix = CrownLocation.currentFix(context.applicationContext) ?: return null
    return IncidentClearFix(
        latitude = fix.latitude,
        longitude = fix.longitude,
        capturedAtIso = Instant.ofEpochMilli(fix.recordedAtMillis).toString(),
        accuracyMeters = fix.accuracyMeters,
        // Reported truthfully and never suppressed: the backend treats it as a
        // one-way signal, so an honest client loses nothing and a dishonest one
        // gains nothing by lying.
        isMock = fix.isMock == true,
    )
}
