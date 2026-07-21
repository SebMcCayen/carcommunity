package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping of the `drives-save` callable body to a [DriveSaveResult]. The key
 * case is fail-fast on a missing/blank rideId: a malformed success must NOT
 * fake-succeed the save (which would skip the route upload), it must surface as a
 * [DriveSaveException].
 */
class SaveResultMappingTest {

    @Test
    fun `full body maps every field`() {
        val result =
            mapSaveResult(
                mapOf(
                    "rideId" to "ride-1",
                    "routePath" to "rideRoutes/u/ride-1/route.bin",
                    "alreadySaved" to true,
                ),
            )

        assertEquals("ride-1", result.rideId)
        assertEquals("rideRoutes/u/ride-1/route.bin", result.routePath)
        assertTrue(result.alreadySaved)
    }

    @Test
    fun `missing routePath is tolerated and skips the upload`() {
        val result = mapSaveResult(mapOf("rideId" to "ride-1"))

        assertEquals("ride-1", result.rideId)
        assertNull(result.routePath)
        assertFalse(result.alreadySaved)
    }

    @Test
    fun `absent rideId fails fast rather than fake-succeeding`() {
        val error =
            assertThrows(DriveSaveException::class.java) {
                mapSaveResult(mapOf("routePath" to "rideRoutes/u/x/route.bin"))
            }

        // Unclassified (transport succeeded, body was malformed) — never a refusal.
        assertNull(error.code)
    }

    @Test
    fun `blank rideId fails fast`() {
        assertThrows(DriveSaveException::class.java) {
            mapSaveResult(mapOf("rideId" to "   "))
        }
    }

    @Test
    fun `null body fails fast`() {
        assertThrows(DriveSaveException::class.java) {
            mapSaveResult(null)
        }
    }
}
