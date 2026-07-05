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
}
