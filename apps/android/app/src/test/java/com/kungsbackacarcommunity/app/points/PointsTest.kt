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
}
