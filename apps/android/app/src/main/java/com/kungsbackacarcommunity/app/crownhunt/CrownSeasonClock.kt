package com.kungsbackacarcommunity.app.crownhunt

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The current Kronjakt SEASON id, client side.
 *
 * A season is one calendar MONTH in Europe/Stockholm and its id is `YYYY-MM`,
 * mirroring `seasonIdForInstant` in
 * `functions/src/crownHunt/crown-hunt-stats-core.ts`. The client needs it only to
 * pick WHICH leaderboard scope to read for "this season's top score"; the backend
 * remains authoritative for every count, so a client that computed the wrong
 * month (it will not — the zone and format are identical) would at worst read an
 * empty board, never award a point.
 *
 * Pure over an injected clock + zone, so the month-boundary behaviour is unit
 * tested off-device.
 */
object CrownSeasonClock {
    private val STOCKHOLM: ZoneId = ZoneId.of("Europe/Stockholm")
    private val SEASON_ID: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    /** The `YYYY-MM` season id [instant] falls in, in [zone] (default Stockholm). */
    fun seasonIdForInstant(instant: Instant, zone: ZoneId = STOCKHOLM): String =
        SEASON_ID.format(instant.atZone(zone))

    /** The current `YYYY-MM` season id. */
    fun currentSeasonId(now: Instant = Instant.now(), zone: ZoneId = STOCKHOLM): String =
        seasonIdForInstant(now, zone)

    /** The reserved scope id for the never-resetting all-time board. */
    const val ALL_TIME_SCOPE: String = "alltime"
}
