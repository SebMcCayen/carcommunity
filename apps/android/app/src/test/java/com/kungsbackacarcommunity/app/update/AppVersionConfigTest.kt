package com.kungsbackacarcommunity.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing of the server-held `config/appVersion` document.
 *
 * The theme of every test here is the same: anything the app cannot make
 * confident sense of becomes null, and null means "show nothing". A bad
 * value in this document must never be able to put a wall in front of a
 * working app.
 */
class AppVersionConfigTest {

    @Test
    fun `parses a full document`() {
        val config =
            AppVersionConfig.fromStored(
                mapOf(
                    "latestVersionCode" to 23L,
                    "latestVersionName" to "0.8.12",
                    "minimumSupportedVersionCode" to 20L,
                ),
            )
        assertEquals(AppVersionConfig(23, "0.8.12", 20), config)
    }

    @Test
    fun `defaults the minimum to zero when absent`() {
        val config = AppVersionConfig.fromStored(mapOf("latestVersionCode" to 23L))
        assertEquals(AppVersionConfig(23, null, 0), config)
    }

    @Test
    fun `accepts Int and whole Double numbers as well as Long`() {
        assertEquals(23, AppVersionConfig.fromStored(mapOf("latestVersionCode" to 23))?.latestVersionCode)
        assertEquals(
            23,
            AppVersionConfig.fromStored(mapOf("latestVersionCode" to 23.0))?.latestVersionCode,
        )
    }

    @Test
    fun `blank version names become null rather than an empty dialog line`() {
        val config =
            AppVersionConfig.fromStored(
                mapOf("latestVersionCode" to 23L, "latestVersionName" to "   "),
            )
        assertNull(config?.latestVersionName)
    }

    @Test
    fun `trims the version name`() {
        val config =
            AppVersionConfig.fromStored(
                mapOf("latestVersionCode" to 23L, "latestVersionName" to " 0.8.12 "),
            )
        assertEquals("0.8.12", config?.latestVersionName)
    }

    @Test
    fun `a non-string version name is ignored`() {
        val config =
            AppVersionConfig.fromStored(
                mapOf("latestVersionCode" to 23L, "latestVersionName" to 812L),
            )
        assertEquals(AppVersionConfig(23, null, 0), config)
    }

    @Test
    fun `a null or empty document parses to null`() {
        assertNull(AppVersionConfig.fromStored(null))
        assertNull(AppVersionConfig.fromStored(emptyMap()))
    }

    @Test
    fun `a missing, wrongly typed or nonsense latestVersionCode parses to null`() {
        assertNull(AppVersionConfig.fromStored(mapOf("latestVersionName" to "0.8.12")))
        // Not coerced: a string means whoever wrote the document did not
        // understand the contract, and guessing is how you brick an app.
        assertNull(AppVersionConfig.fromStored(mapOf("latestVersionCode" to "23")))
        assertNull(AppVersionConfig.fromStored(mapOf("latestVersionCode" to true)))
        assertNull(AppVersionConfig.fromStored(mapOf("latestVersionCode" to null)))
        assertNull(AppVersionConfig.fromStored(mapOf("latestVersionCode" to -1L)))
        assertNull(AppVersionConfig.fromStored(mapOf("latestVersionCode" to 23.5)))
        assertNull(AppVersionConfig.fromStored(mapOf("latestVersionCode" to Double.NaN)))
        assertNull(
            AppVersionConfig.fromStored(mapOf("latestVersionCode" to Double.POSITIVE_INFINITY)),
        )
        assertNull(
            AppVersionConfig.fromStored(mapOf("latestVersionCode" to Int.MAX_VALUE.toLong() + 1)),
        )
    }

    @Test
    fun `a nonsense minimum is discarded rather than taken at face value`() {
        val config =
            AppVersionConfig.fromStored(
                mapOf(
                    "latestVersionCode" to 23L,
                    "minimumSupportedVersionCode" to "twenty",
                ),
            )
        assertEquals(0, config?.minimumSupportedVersionCode)
    }

    @Test
    fun `a minimum above the latest version is discarded, not obeyed`() {
        // No published build could satisfy it, so obeying it would wall every
        // single user out with no way back in.
        val config =
            AppVersionConfig.fromStored(
                mapOf(
                    "latestVersionCode" to 23L,
                    "minimumSupportedVersionCode" to 99L,
                ),
            )
        assertEquals(AppVersionConfig(23, null, 0), config)
    }

    @Test
    fun `a minimum equal to the latest version is kept`() {
        val config =
            AppVersionConfig.fromStored(
                mapOf(
                    "latestVersionCode" to 23L,
                    "minimumSupportedVersionCode" to 23L,
                ),
            )
        assertEquals(23, config?.minimumSupportedVersionCode)
    }
}
