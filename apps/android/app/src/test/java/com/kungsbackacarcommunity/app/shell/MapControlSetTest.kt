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
    fun `full stack is report, layers, compass, saved places, chat, perks`() {
        assertEquals(
            listOf(
                MapCircleControlKind.Report,
                MapCircleControlKind.Layers,
                MapCircleControlKind.Compass,
                MapCircleControlKind.SavedPlaces,
                MapCircleControlKind.Chat,
                MapCircleControlKind.Perks,
            ),
            MapControlSet.rightSideStack(
                incidentReportingEnabled = true,
                crownHuntPerksEnabled = true,
            ),
        )
    }

    /**
     * Both flags default off-ish: reporting on, perks off (the `crownHuntPerks`
     * default). The perks control is absent unless explicitly enabled, so an
     * existing caller that never wired it — and every map-home test — is
     * unaffected.
     */
    @Test
    fun `perks control is absent by default`() {
        assertEquals(
            listOf(
                MapCircleControlKind.Report,
                MapCircleControlKind.Layers,
                MapCircleControlKind.Compass,
                MapCircleControlKind.SavedPlaces,
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
                MapCircleControlKind.SavedPlaces,
                MapCircleControlKind.Chat,
            ),
            MapControlSet.rightSideStack(incidentReportingEnabled = false),
        )
    }

    /**
     * The report control is the ONLY conditional one at the TOP. Toggling the
     * reporting gate must never add or remove anything but [Report].
     */
    @Test
    fun `reporting gate only ever adds or removes the report control`() {
        val withReporting =
            MapControlSet.rightSideStack(incidentReportingEnabled = true, crownHuntPerksEnabled = false)
        val without =
            MapControlSet.rightSideStack(incidentReportingEnabled = false, crownHuntPerksEnabled = false)
        assertEquals(
            listOf(MapCircleControlKind.Report),
            withReporting - without.toSet(),
        )
        assertEquals(emptyList<MapCircleControlKind>(), without - withReporting.toSet())
    }

    /**
     * The perks control is the ONLY conditional one at the BOTTOM. Toggling the
     * perks gate must never add or remove anything but [Perks], and it is
     * appended AFTER chat.
     */
    @Test
    fun `perks gate only ever adds or removes the perks control`() {
        val withPerks =
            MapControlSet.rightSideStack(incidentReportingEnabled = true, crownHuntPerksEnabled = true)
        val without =
            MapControlSet.rightSideStack(incidentReportingEnabled = true, crownHuntPerksEnabled = false)
        assertEquals(
            listOf(MapCircleControlKind.Perks),
            withPerks - without.toSet(),
        )
        assertEquals(emptyList<MapCircleControlKind>(), without - withPerks.toSet())
        // Appended last, after chat.
        assertEquals(MapCircleControlKind.Perks, withPerks.last())
    }

    /**
     * Every declared kind is actually drawn somewhere. A control kind that never
     * appears in the stack is dead weight the exhaustive `when`s still have to
     * carry.
     */
    @Test
    fun `every control kind appears in the fully-enabled stack`() {
        assertEquals(
            MapCircleControlKind.entries.toSet(),
            MapControlSet.rightSideStack(
                incidentReportingEnabled = true,
                crownHuntPerksEnabled = true,
            ).toSet(),
        )
    }

    /** No duplicates: each control appears exactly once. */
    @Test
    fun `no control is rendered twice`() {
        val stack =
            MapControlSet.rightSideStack(incidentReportingEnabled = true, crownHuntPerksEnabled = true)
        assertEquals(stack.size, stack.distinct().size)
    }
}
