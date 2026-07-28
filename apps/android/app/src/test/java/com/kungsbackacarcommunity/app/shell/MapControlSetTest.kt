package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the bottom-right control stack.
 *
 * The requirement is literal: turn-by-turn shows "the same round buttons on the
 * right side as on the main page, with the same functions… not more, not fewer,
 * and no other type of button". Both screens render
 * [MapControlSet.rightSideStack] through an exhaustive `when`, so this test plus
 * the compiler is what enforces it — asserting the LIST catches a control being
 * added, removed or reordered, and the exhaustive `when` catches a screen
 * forgetting to draw one.
 */
class MapControlSetTest {
    @Test
    fun `full stack is report, layers, compass, recenter, chat`() {
        assertEquals(
            listOf(
                MapCircleControlKind.Report,
                MapCircleControlKind.Layers,
                MapCircleControlKind.Compass,
                MapCircleControlKind.Recenter,
                MapCircleControlKind.Chat,
            ),
            MapControlSet.rightSideStack(incidentReportingEnabled = true),
        )
    }

    @Test
    fun `without reporting the stack closes up rather than leaving a gap`() {
        assertEquals(
            listOf(
                MapCircleControlKind.Layers,
                MapCircleControlKind.Compass,
                MapCircleControlKind.Recenter,
                MapCircleControlKind.Chat,
            ),
            MapControlSet.rightSideStack(incidentReportingEnabled = false),
        )
    }

    /**
     * The report control is the ONLY conditional one. Anything else appearing or
     * disappearing with the reporting gate would be a stack that changes shape
     * for an unrelated reason.
     */
    @Test
    fun `reporting gate only ever adds or removes the report control`() {
        val withReporting = MapControlSet.rightSideStack(incidentReportingEnabled = true)
        val without = MapControlSet.rightSideStack(incidentReportingEnabled = false)
        assertEquals(
            listOf(MapCircleControlKind.Report),
            withReporting - without.toSet(),
        )
        assertEquals(emptyList<MapCircleControlKind>(), without - withReporting.toSet())
    }

    /**
     * Every declared kind is actually drawn somewhere. A control kind that never
     * appears in the stack is dead weight the exhaustive `when`s still have to
     * carry.
     */
    @Test
    fun `every control kind appears in the full stack`() {
        assertEquals(
            MapCircleControlKind.entries.toSet(),
            MapControlSet.rightSideStack(incidentReportingEnabled = true).toSet(),
        )
    }

    /** No duplicates: each control appears exactly once. */
    @Test
    fun `no control is rendered twice`() {
        val stack = MapControlSet.rightSideStack(incidentReportingEnabled = true)
        assertEquals(stack.size, stack.distinct().size)
    }
}
