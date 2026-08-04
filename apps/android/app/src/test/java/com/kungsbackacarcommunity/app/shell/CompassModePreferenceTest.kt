package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The compass-orientation default/persistence DECISION logic, pure Kotlin so it
 * runs on the JVM. The SharedPreferences plumbing ([CompassModePreferenceStore])
 * is a thin wrapper over these functions; the Compose side (that the chosen mode
 * reaches the surface, and the toggle's re-centre behaviour) is covered by the
 * shell/seam tests (`MapFirstShellTest`, `CompassModeSeamTest`).
 */
class CompassModePreferenceTest {

    /**
     * The headline behaviour change: a user who has NEVER chosen an orientation
     * (first run / unset preference) gets COURSE-UP — the map rotates with the
     * direction of travel out of the box.
     */
    @Test
    fun `no stored preference defaults to course-up`() {
        assertEquals(MapCompassMode.CourseUp, MapCompassMode.fromStoredName(null))
        assertEquals(MapCompassMode.CourseUp, MapCompassMode.DEFAULT)
    }

    /** A user who has picked north-up keeps north-up across a restart. */
    @Test
    fun `stored north-up stays north-up`() {
        assertEquals(
            MapCompassMode.NorthUp,
            MapCompassMode.fromStoredName(MapCompassMode.NorthUp.name),
        )
    }

    /** A user who has (re)picked course-up keeps course-up across a restart. */
    @Test
    fun `stored course-up stays course-up`() {
        assertEquals(
            MapCompassMode.CourseUp,
            MapCompassMode.fromStoredName(MapCompassMode.CourseUp.name),
        )
    }

    /** Every stored name round-trips through the parser. */
    @Test
    fun `stored names round-trip`() {
        MapCompassMode.entries.forEach { mode ->
            assertEquals(mode, MapCompassMode.fromStoredName(mode.name))
        }
    }

    /**
     * A value this build no longer knows (enum renamed by an update, hand-edited
     * prefs) or an empty string must fall back to the course-up default rather
     * than throw the way `valueOf` would — this parse runs during map start-up, so
     * a throw would be a launch crash.
     */
    @Test
    fun `unknown or empty stored names fall back to the course-up default`() {
        assertEquals(MapCompassMode.CourseUp, MapCompassMode.fromStoredName(""))
        assertEquals(MapCompassMode.CourseUp, MapCompassMode.fromStoredName("HeadingUp"))
        // Case-sensitive, like the theme parser: a lowercased name is unknown.
        assertEquals(MapCompassMode.CourseUp, MapCompassMode.fromStoredName("courseup"))
    }

    /**
     * The compass control's tap contract: each tap writes the OPPOSITE mode.
     * Toggling twice is the identity, so the button always alternates.
     */
    @Test
    fun `a tap toggles to the opposite mode`() {
        assertEquals(MapCompassMode.CourseUp, MapCompassMode.NorthUp.toggled())
        assertEquals(MapCompassMode.NorthUp, MapCompassMode.CourseUp.toggled())
        MapCompassMode.entries.forEach { mode ->
            assertEquals("toggling twice returns to $mode", mode, mode.toggled().toggled())
        }
    }
}
