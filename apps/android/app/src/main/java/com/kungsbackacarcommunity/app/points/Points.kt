package com.kungsbackacarcommunity.app.points

/**
 * Kronpoäng (points) domain (Phase 12 slice 15). Read-only: the balance lives
 * on `pointsLedger/{uid}.balance` and the append-only entries at
 * `pointsLedger/{uid}/entries` (owner read, backend-only writes; a balance can
 * never go negative). Pure Kotlin.
 */
data class PointsEntry(
    val id: String,
    /** Signed: positive = credit, negative = debit. */
    val amount: Long,
    val balanceAfter: Long?,
    val description: String,
    val createdAtMillis: Long?,
)

object Points {
    /** Newest transaction first; undated entries sort last. */
    fun sortedForList(entries: List<PointsEntry>): List<PointsEntry> =
        entries.sortedByDescending { it.createdAtMillis ?: Long.MIN_VALUE }

    /** Default number of recent earnings shown on the profile. */
    const val PROFILE_HIGHLIGHT_COUNT = 4

    /**
     * The newest few EARNINGS, for the "why did I get these points?" line on the
     * profile.
     *
     * Credits only: a debit (a redeemed reward) answers a different question and
     * would read as a punishment next to a badge wall, so it is left to the full
     * Kronpoäng screen. Input order is not trusted — the list is re-sorted with
     * [sortedForList] so an unordered snapshot still yields the genuinely newest
     * entries.
     */
    fun recentEarnings(
        entries: List<PointsEntry>,
        limit: Int = PROFILE_HIGHLIGHT_COUNT,
    ): List<PointsEntry> {
        if (limit <= 0) return emptyList()
        return sortedForList(entries.filter { it.amount > 0 }).take(limit)
    }
}
