package com.kungsbackacarcommunity.app.crownhunt

/**
 * Picks the two fixes a spawn claim is proved with, gated on the crown's own
 * geofence — the pure half of the crown popup's collect wiring, kept out of the
 * Compose host so the selection (and the fail-closed edge cases) are unit-tested.
 *
 * ## Why the geofence gate is on BOTH halves (#911)
 *
 * The server refuses a claim `outside_radius` when EITHER proof fix is outside
 * the collect ring, but the client used to range-check only the CURRENT fix. The
 * dwell tracker is fed by a PRE-WARM poll that runs while the member is still
 * APPROACHING, so its buffer holds approach-era fixes recorded from farther out;
 * pairing a dead-on current with one of those made the button read Ready and the
 * first tap was refused `outside_radius` — the "tap, out of range, restart, works"
 * report. Requiring the PARTNER to be in range too makes the client's Ready match
 * what the server accepts. This only ever makes the client STRICTER, so the
 * anti-spoof geofence stays the authority.
 */
object CrownProofSelection {
    /**
     * Whether a fix is inside [crown]'s collect radius — the client mirror of the
     * server's per-fix geofence test, used to gate BOTH halves of the dwell pair.
     *
     * FAILS CLOSED on a null crown: until the tapped crown has latched there is no
     * geofence to judge a fix against, so NO fix counts as in range and no proof
     * pair can read as ready. Accepting any fix in that window would let an
     * out-of-range pre-warmed pair momentarily show Ready in the frame between the
     * popup opening and the crown latching — the exact #911 shape, sneaking back
     * for a frame. The predicate flips to the real geofence check the instant the
     * crown is non-null.
     *
     * The plain distance-vs-radius rule is the same [CrownRange] uses for the
     * marker and the "too far" line, so a pair the client calls collectable agrees
     * with where the ring is drawn.
     */
    fun inRangePredicate(crown: CrownSpawn?): (CrownFix) -> Boolean {
        if (crown == null) return { false }
        return { fix ->
            CrownRange.isInRange(
                CrownSpawnQuery.distanceMeters(
                    fix.latitude,
                    fix.longitude,
                    crown.latitude,
                    crown.longitude,
                ),
                crown.collectRadiusMeters,
            )
        }
    }

    /**
     * Chooses the (current, previous) fixes that drive the crown popup from
     * [tracker] at wall-clock [nowMillis], for the open [crown].
     *
     * Prefers a valid dwell PAIR ([CrownFixTracker.proofPair]) so Collect goes live
     * whenever a claim is actually possible — never stranded in "confirming" while
     * a usable pair sits in the buffer. When no in-range pair is achievable yet it
     * still returns a fresh best-accuracy current for the distance line, with a
     * null partner so the gate honestly shows the confirming state.
     *
     * Both halves are gated on [inRangePredicate], so a null [crown] yields a null
     * partner (no Ready) until the crown latches (#911).
     */
    fun selectFixes(
        tracker: CrownFixTracker,
        nowMillis: Long,
        crown: CrownSpawn?,
    ): Pair<CrownFix?, CrownFix?> {
        val pair = tracker.proofPair(nowMillis, inRangePredicate(crown))
        return if (pair != null) {
            pair.current to pair.previous
        } else {
            tracker.bestRecent(nowMillis) to null
        }
    }
}
