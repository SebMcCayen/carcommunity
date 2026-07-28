package com.kungsbackacarcommunity.app.points

import org.junit.Assert.assertEquals
import org.junit.Test

class PointsTest {

    @Test
    fun `sortedForList is newest first with undated last`() {
        val entries =
            listOf(
                PointsEntry("a", 10, 10, "Credit", 100L),
                PointsEntry("b", -5, 5, "Debit", null),
                PointsEntry("c", 20, 25, "Credit", 300L),
                PointsEntry("d", 5, 30, "Credit", 200L),
            )
        assertEquals(listOf("c", "d", "a", "b"), Points.sortedForList(entries).map { it.id })
    }

    @Test
    fun `recentEarnings keeps credits only, newest first, capped`() {
        val entries =
            listOf(
                PointsEntry("old", 5, 5, "Sparad körning", 100L),
                PointsEntry("spend", -50, 55, "Inlöst förmån", 500L),
                PointsEntry("newest", 25, 105, "Märke upplåst: Kronjägare Brons", 400L),
                PointsEntry("mid", 20, 80, "Krona insamlad", 300L),
                PointsEntry("zero", 0, 80, "Justering", 350L),
            )

        // A debit answers a different question, and a zero is not an earning.
        assertEquals(
            listOf("newest", "mid", "old"),
            Points.recentEarnings(entries).map { it.id },
        )
        assertEquals(listOf("newest", "mid"), Points.recentEarnings(entries, limit = 2).map { it.id })
        assertEquals(emptyList<String>(), Points.recentEarnings(entries, limit = 0).map { it.id })
    }

    @Test
    fun `recentEarnings does not trust the input order`() {
        val entries =
            listOf(
                PointsEntry("a", 10, 10, "Credit", 100L),
                PointsEntry("c", 10, 30, "Credit", 300L),
                PointsEntry("b", 10, 20, "Credit", 200L),
            )
        assertEquals(listOf("c", "b"), Points.recentEarnings(entries, limit = 2).map { it.id })
    }

    @Test
    fun `an empty ledger yields no highlights rather than an error`() {
        assertEquals(emptyList<PointsEntry>(), Points.recentEarnings(emptyList()))
    }
}
