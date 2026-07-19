package com.kungsbackacarcommunity.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the feature-health DECISION logic.
 *
 * This logic is where the feature becomes actively harmful if it is wrong: a
 * false positive files a world-readable GitHub issue against a user who was
 * merely in a tunnel, and a volume leak spams issues until someone mutes the
 * label — after which it catches nothing at all. So the suppression rules and
 * the per-session cap are tested directly rather than inferred from the UI.
 */
class FeatureHealthTest {

    private fun environment(
        appVersionName: String = "0.8.1",
        navSdkEnabled: Boolean = true,
        accessTokenPresent: Boolean = true,
    ) = FeatureHealthEnvironment(
        appVersionName = appVersionName,
        appVersionCode = 811L,
        navSdkEnabled = navSdkEnabled,
        androidApiLevel = 34,
        mapboxMapsSdkVersion = "11.26.0",
        accessTokenPresent = accessTokenPresent,
    )

    private fun healthy() =
        FeatureHealthConditions(online = true, foreground = true, surfaceShown = true)

    // ---- Suppression: the anti-false-positive rules --------------------------

    @Test
    fun `reports a map render timeout under healthy conditions`() {
        val decision = FeatureHealthGate(environment())
            .decide(FeatureHealthKind.MapRenderTimeout, healthy())
        assertTrue(decision is FeatureHealthDecision.Report)
    }

    @Test
    fun `suppresses every kind while offline`() {
        // The tunnel / plane / no-data case. Tiles cannot load, the map is blank,
        // and NONE of that is a defect.
        val gate = FeatureHealthGate(environment())
        FeatureHealthKind.entries.forEach { kind ->
            val decision = gate.decide(kind, healthy().copy(online = false))
            assertEquals(
                "$kind must be suppressed offline",
                FeatureHealthDecision.Suppress(FeatureHealthSuppression.Offline),
                decision,
            )
        }
    }

    @Test
    fun `suppresses every kind while backgrounded`() {
        val gate = FeatureHealthGate(environment())
        FeatureHealthKind.entries.forEach { kind ->
            assertEquals(
                "$kind must be suppressed while backgrounded",
                FeatureHealthDecision.Suppress(FeatureHealthSuppression.Backgrounded),
                gate.decide(kind, healthy().copy(foreground = false)),
            )
        }
    }

    @Test
    fun `suppresses every kind when the surface was never shown`() {
        val gate = FeatureHealthGate(environment())
        FeatureHealthKind.entries.forEach { kind ->
            assertEquals(
                "$kind must be suppressed when the surface never appeared",
                FeatureHealthDecision.Suppress(FeatureHealthSuppression.SurfaceNeverShown),
                gate.decide(kind, healthy().copy(surfaceShown = false)),
            )
        }
    }

    @Test
    fun `suppresses nav kinds when the nav SDK is not in the build`() {
        val gate = FeatureHealthGate(environment(navSdkEnabled = false))
        assertEquals(
            FeatureHealthDecision.Suppress(FeatureHealthSuppression.NavSdkDisabled),
            gate.decide(FeatureHealthKind.NavSessionInitFailed, healthy()),
        )
        assertEquals(
            FeatureHealthDecision.Suppress(FeatureHealthSuppression.NavSdkDisabled),
            gate.decide(FeatureHealthKind.NavRouteRequestFailed, healthy()),
        )
        // Map kinds are unaffected — the map ships in every build.
        assertTrue(
            gate.decide(FeatureHealthKind.MapRenderTimeout, healthy())
                is FeatureHealthDecision.Report,
        )
    }

    // ---- Volume: the once-per-session cap ------------------------------------

    @Test
    fun `reports a kind at most once per session`() {
        val gate = FeatureHealthGate(environment())
        assertTrue(
            gate.decide(FeatureHealthKind.MapStyleLoadFailed, healthy())
                is FeatureHealthDecision.Report,
        )
        repeat(5) {
            assertEquals(
                FeatureHealthDecision.Suppress(
                    FeatureHealthSuppression.AlreadyReportedThisSession,
                ),
                gate.decide(FeatureHealthKind.MapStyleLoadFailed, healthy()),
            )
        }
    }

    @Test
    fun `the once-per-session cap is per kind, not global`() {
        val gate = FeatureHealthGate(environment())
        // A distinct defect must still be reportable after another one fired.
        FeatureHealthKind.entries.forEach { kind ->
            assertTrue(
                "$kind should still be reportable",
                gate.decide(kind, healthy()) is FeatureHealthDecision.Report,
            )
        }
    }

    @Test
    fun `a suppressed attempt does not consume the session budget`() {
        // The important ordering property: failing while offline must not use up
        // the one report slot, or a user who starts in a tunnel is permanently
        // unable to report a genuine defect for the rest of the session.
        val gate = FeatureHealthGate(environment())
        gate.decide(FeatureHealthKind.MapRenderTimeout, healthy().copy(online = false))
        gate.decide(FeatureHealthKind.MapRenderTimeout, healthy().copy(foreground = false))
        gate.decide(FeatureHealthKind.MapRenderTimeout, healthy().copy(surfaceShown = false))
        assertFalse(gate.hasReported(FeatureHealthKind.MapRenderTimeout))
        assertTrue(
            gate.decide(FeatureHealthKind.MapRenderTimeout, healthy())
                is FeatureHealthDecision.Report,
        )
    }

    // ---- Fingerprint composition ---------------------------------------------

    @Test
    fun `fingerprint inputs are identical across devices for the same defect`() {
        // The backend fingerprint is sha256(feature | code). Two different
        // devices on the same build must produce byte-identical inputs so a
        // fleet-wide defect collapses to ONE issue.
        val deviceA = FeatureHealthGate(environment())
            .decide(FeatureHealthKind.MapRenderTimeout, healthy())
        val deviceB = FeatureHealthGate(environment())
            .decide(FeatureHealthKind.MapRenderTimeout, healthy())
        deviceA as FeatureHealthDecision.Report
        deviceB as FeatureHealthDecision.Report
        assertEquals(deviceA.feature, deviceB.feature)
        assertEquals(deviceA.code, deviceB.code)
    }

    @Test
    fun `distinct defects never share a fingerprint`() {
        val codes = FeatureHealthKind.entries.map { kind ->
            val decision = FeatureHealthGate(environment()).decide(kind, healthy())
            decision as FeatureHealthDecision.Report
            decision.feature to decision.code
        }
        assertEquals("every kind must be uniquely keyed", codes.size, codes.toSet().size)
    }

    @Test
    fun `fingerprint is scoped by app version so a regression files a fresh issue`() {
        val old = FeatureHealthGate(environment(appVersionName = "0.8.1"))
            .decide(FeatureHealthKind.MapRenderTimeout, healthy())
        val new = FeatureHealthGate(environment(appVersionName = "0.9.3"))
            .decide(FeatureHealthKind.MapRenderTimeout, healthy())
        old as FeatureHealthDecision.Report
        new as FeatureHealthDecision.Report
        assertEquals(old.feature, new.feature)
        assertNotEquals(old.code, new.code)
        assertEquals("MAP_RENDER_TIMEOUT@0.8.1", old.code)
        assertEquals("MAP_RENDER_TIMEOUT@0.9.3", new.code)
    }

    @Test
    fun `a hostile version name cannot bloat or fragment the dedup key`() {
        val decision = FeatureHealthGate(
            environment(appVersionName = "1.0 (dirty)/../../etc" + "x".repeat(100)),
        ).decide(FeatureHealthKind.MapRenderTimeout, healthy())
        decision as FeatureHealthDecision.Report
        val version = decision.code.substringAfter('@')
        assertTrue("version must be bounded", version.length <= 24)
        assertTrue(
            "version must be alphanumeric/.-_ only",
            version.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' },
        )
    }

    // ---- Payload privacy ------------------------------------------------------

    @Test
    fun `payload carries no secret and no user data`() {
        // The issue is PUBLIC. Assert the shape of what actually goes on the wire.
        val decision = FeatureHealthGate(environment())
            .decide(FeatureHealthKind.MapRenderTimeout, healthy())
        decision as FeatureHealthDecision.Report
        val payload = "${decision.feature} ${decision.code} ${decision.message}"
        // Only a boolean about the token, never a token value. Real Mapbox tokens
        // are "pk."/"sk." prefixed.
        assertFalse(payload.contains("pk."))
        assertFalse(payload.contains("sk."))
        assertTrue(decision.message.contains("tokenPresent=true"))
        // The build facts triage needs, and nothing beyond them.
        assertTrue(decision.message.contains("maps=11.26.0"))
        assertTrue(decision.message.contains("navSdk=true"))
        assertTrue(decision.message.contains("api=34"))
    }

    // ---- MapLoadingError classification ---------------------------------------

    @Test
    fun `whole-map resource failures are reportable`() {
        assertEquals(
            FeatureHealthKind.MapStyleLoadFailed,
            mapLoadingErrorKindFor("STYLE"),
        )
        assertEquals(
            FeatureHealthKind.MapResourceLoadError,
            mapLoadingErrorKindFor("SPRITE"),
        )
        assertEquals(
            FeatureHealthKind.MapResourceLoadError,
            mapLoadingErrorKindFor("GLYPHS"),
        )
    }

    @Test
    fun `viewport-scoped tile and source failures are ignored`() {
        // These fire routinely when the user pans to an uncached area or the
        // connection wobbles. A map missing one tile is not a broken map, and
        // reporting these would bury the real defects.
        assertNull(mapLoadingErrorKindFor("TILE"))
        assertNull(mapLoadingErrorKindFor("SOURCE"))
        // Unknown future enum values default to "don't report".
        assertNull(mapLoadingErrorKindFor("SOMETHING_NEW"))
    }
}
