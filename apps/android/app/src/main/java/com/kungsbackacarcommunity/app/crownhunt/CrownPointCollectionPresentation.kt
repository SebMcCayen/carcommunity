package com.kungsbackacarcommunity.app.crownhunt

import java.time.Instant
import java.time.ZoneOffset

/**
 * Short-lived map suppression after an admin point award, never an eligibility cache.
 *
 * The backend supports once/daily/weekly UTC windows, but [CrownHuntPoint] exposes
 * neither repeatRule nor a next-eligible timestamp. Hide only until the next UTC
 * day (the earliest possible repeat window), or until the uid's map composition
 * ends. Once/weekly points can reappear before eligible; the server still decides.
 * No claim-history reads, persistence, or inference from rejection codes.
 */
object CrownPointCollectionPresentation {
    const val MAX_ENTRIES: Int = 200

    fun afterClaim(
        suppressedUntil: Map<String, Long>,
        pointId: String,
        status: CrownHuntClaimStatus,
        nowMillis: Long,
    ): Map<String, Long> {
        if ((status as? CrownHuntClaimStatus.Done)?.outcome?.result != CrownHuntClaimResult.AWARDED) {
            return suppressedUntil
        }
        val nextUtcDay =
            Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate()
                .plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // Refresh insertion order for this point; retain only a bounded set of recent awards.
        return (prune(suppressedUntil, nowMillis) - pointId + (pointId to nextUtcDay))
            .entries.toList().takeLast(MAX_ENTRIES).associate { it.toPair() }
    }

    fun prune(suppressedUntil: Map<String, Long>, nowMillis: Long): Map<String, Long> =
        suppressedUntil.filterValues { it > nowMillis }
}
