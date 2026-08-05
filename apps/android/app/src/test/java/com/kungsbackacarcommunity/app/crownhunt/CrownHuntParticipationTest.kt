package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one load-bearing rule of the participation preference: an UNSET value
 * reads as participating, so a fresh install shows the game to everyone and a
 * member only ever leaves it by their own explicit choice.
 *
 * Pure — [CrownHuntParticipation.fromStored] is the whole decision, kept free of
 * `SharedPreferences` so this needs no device. The store's disk round-trip is a
 * thin wrapper over exactly this call.
 */
class CrownHuntParticipationTest {
    @Test
    fun unsetMeansParticipating() {
        assertTrue("nothing stored yet must read as participating", CrownHuntParticipation.fromStored(null))
        assertTrue("the default itself is participating", CrownHuntParticipation.DEFAULT_PARTICIPATING)
    }

    @Test
    fun aStoredChoiceIsTakenAsIs() {
        // The round-trip both directions: opting out then in must be honoured,
        // never overridden by the default. This is the persistence contract the
        // SharedPreferences store relies on.
        assertTrue("stored true → participating", CrownHuntParticipation.fromStored(true))
        assertFalse("stored false → opted out", CrownHuntParticipation.fromStored(false))
    }

    @Test
    fun defaultRoundTripsThroughFromStoredUnchanged() {
        // Storing the default and reading it back yields the default — the store
        // never flips a value on the way through.
        assertEquals(
            CrownHuntParticipation.DEFAULT_PARTICIPATING,
            CrownHuntParticipation.fromStored(CrownHuntParticipation.DEFAULT_PARTICIPATING),
        )
    }
}
