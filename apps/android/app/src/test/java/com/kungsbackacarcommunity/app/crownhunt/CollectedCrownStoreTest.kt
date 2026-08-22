package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure (de)serialization half of [PrefsCollectedCrownStore].
 *
 * The prefs/Context half needs an Android [android.content.Context] and so cannot
 * be constructed in a JVM unit test, but [PrefsCollectedCrownStore.encode] /
 * [PrefsCollectedCrownStore.decode] are where the id -> expiry map is round-
 * tripped and a corrupt SharedPreferences payload is disarmed — the crux of the
 * durability fix — so they are pinned here directly.
 */
class CollectedCrownStoreTest {

    @Test
    fun `encode then decode round-trips ids and expiries`() {
        val original =
            mapOf(
                "spawnA" to 2_000_000L,
                "spawnB" to 9_999_999_999L,
                // A crown whose document omitted an expiry.
                "spawnC" to null,
            )

        val restored = PrefsCollectedCrownStore.decode(PrefsCollectedCrownStore.encode(original))

        assertEquals(original, restored)
    }

    @Test
    fun `a null expiry survives the round-trip as null, not as a sentinel number`() {
        val restored =
            PrefsCollectedCrownStore.decode(
                PrefsCollectedCrownStore.encode(mapOf("s" to null)),
            )

        assertTrue("the key must be present", restored.containsKey("s"))
        assertEquals("a null expiry must decode back to null", null, restored["s"])
    }

    @Test
    fun `an empty map round-trips to empty`() {
        assertTrue(
            PrefsCollectedCrownStore.decode(PrefsCollectedCrownStore.encode(emptyMap())).isEmpty(),
        )
    }

    @Test
    fun `a null or blank payload decodes to empty rather than throwing`() {
        assertTrue(PrefsCollectedCrownStore.decode(null).isEmpty())
        assertTrue(PrefsCollectedCrownStore.decode("").isEmpty())
        assertTrue(PrefsCollectedCrownStore.decode("   ").isEmpty())
    }

    @Test
    fun `blank ids are dropped on encode`() {
        val restored =
            PrefsCollectedCrownStore.decode(
                PrefsCollectedCrownStore.encode(mapOf("" to 1L, "keep" to 2L)),
            )

        assertEquals(mapOf("keep" to 2L), restored)
    }

    @Test
    fun `a corrupt expiry value decodes as an unknown (null) expiry, never a crash`() {
        // A payload a bad/older writer could leave: a non-numeric expiry.
        val restored = PrefsCollectedCrownStore.decode("""{"s":"not-a-number"}""")

        assertTrue(restored.containsKey("s"))
        assertEquals(null, restored["s"])
    }
}
