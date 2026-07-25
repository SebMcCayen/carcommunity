package com.kungsbackacarcommunity.app.appcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCheckDebugSecretTest {

    private val token = "3f1c9e2a-7b4d-4c8e-9a1f-2d6b0c5e8a11"

    @Test
    fun `normalizeToken trims and treats absent or blank as unconfigured`() {
        assertEquals(token, AppCheckDebugSecret.normalizeToken("  $token \n"))
        assertNull(AppCheckDebugSecret.normalizeToken(null))
        assertNull(AppCheckDebugSecret.normalizeToken(""))
        assertNull(AppCheckDebugSecret.normalizeToken("   "))
    }

    @Test
    fun `seeds only on debug builds with a non-blank token`() {
        assertTrue(AppCheckDebugSecret.shouldSeed(isDebugBuild = true, rawToken = token))

        // The CI / fresh-clone case: no token configured, so the SDK keeps
        // generating its own secret rather than us clearing the store.
        assertFalse(AppCheckDebugSecret.shouldSeed(isDebugBuild = true, rawToken = ""))
        assertFalse(AppCheckDebugSecret.shouldSeed(isDebugBuild = true, rawToken = "   "))
        assertFalse(AppCheckDebugSecret.shouldSeed(isDebugBuild = true, rawToken = null))
    }

    @Test
    fun `never seeds on release builds even when a token is present`() {
        assertFalse(AppCheckDebugSecret.shouldSeed(isDebugBuild = false, rawToken = token))
        assertFalse(AppCheckDebugSecret.shouldSeed(isDebugBuild = false, rawToken = null))
    }

    @Test
    fun `prefs file name carries the per-app persistence key suffix`() {
        // Matches StorageHelper's "com.google.firebase.appcheck.debug.store.%s"
        // template (firebase-appcheck-debug 19.3.0) — the bare, suffix-less name
        // is a different file that the SDK never reads.
        assertEquals(
            "com.google.firebase.appcheck.debug.store.a1B2+c3D4",
            AppCheckDebugSecret.prefsFileName("a1B2+c3D4"),
        )
        assertTrue(
            AppCheckDebugSecret.prefsFileName("x")
                .startsWith(AppCheckDebugSecret.PREFS_NAME_PREFIX),
        )
    }

    @Test
    fun `writes only when the stored secret differs`() {
        assertTrue(AppCheckDebugSecret.shouldWrite(storedSecret = null, desiredSecret = token))
        assertTrue(AppCheckDebugSecret.shouldWrite(storedSecret = "other-secret", desiredSecret = token))
        assertFalse(AppCheckDebugSecret.shouldWrite(storedSecret = token, desiredSecret = token))
    }

    @Test
    fun `secret key matches the SDK constant`() {
        assertEquals(
            "com.google.firebase.appcheck.debug.DEBUG_SECRET",
            AppCheckDebugSecret.DEBUG_SECRET_KEY,
        )
    }
}
