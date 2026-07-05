package com.kungsbackacarcommunity.app.badges

import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeTest {

    @Test
    fun `sortedForList is newest first with undated last`() {
        val badges =
            listOf(
                Badge("first_event", "First event", 100L),
                Badge("early_member", "Early member", null),
                Badge("five_events", "Five events", 300L),
                Badge("garage_created", "Garage", 200L),
            )
        assertEquals(
            listOf("five_events", "garage_created", "first_event", "early_member"),
            Badges.sortedForList(badges).map { it.key },
        )
    }
}
