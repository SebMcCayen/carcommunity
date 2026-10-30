package com.kungsbackacarcommunity.app.profile

import com.kungsbackacarcommunity.app.live.LiveMarkerProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MembershipMapRingTest {
    @Test fun `only exact public eligibility grants a ring including Plus without Supporter flags`() {
        for (eligible in listOf(null, false, "true", 1))
            assertFalse(MembershipMapRing.fromFields(eligible, true).visible)
        val absent = LiveMarkerProfile.fromFields(mapOf("activeMember" to true,
            "supporterBadgeEligible" to true, "subscriptionTier" to "supporter"))
        assertFalse(absent.appearance().showMembershipMapRing)
        val plus = LiveMarkerProfile.fromFields(mapOf("membershipMapRingEligible" to true,
            "supporterBadgeEligible" to false, "subscriptionTier" to "plus"))
        assertTrue(plus.appearance().showMembershipMapRing)
    }

    @Test fun `only the public boolean changes visibility regardless of private billing metadata`() {
        val fields = mapOf("membershipMapRingEligible" to true, "subscriptionStatus" to "cancelled",
            "subscriptions" to mapOf("expiresAt" to 0L), "mapRingRefreshAt" to Any())
        assertTrue(LiveMarkerProfile.fromFields(fields).appearance().showMembershipMapRing)
        assertFalse(LiveMarkerProfile.fromFields(fields + ("membershipMapRingEligible" to false))
            .appearance().showMembershipMapRing)
        assertFalse(LiveMarkerProfile.fromFields(fields - "membershipMapRingEligible")
            .appearance().showMembershipMapRing)
    }

    @Test fun `marker parser accesses only the approved public cosmetic keys`() {
        val allowed = setOf("membershipMapRingEligible", "showMembershipMapRing", "clubBadge")
        val fields = mapOf<String, Any?>("membershipMapRingEligible" to true)
        val guarded = object : Map<String, Any?> by fields {
            override fun get(key: String): Any? {
                check(key in allowed) { "Unexpected profile field access" }
                return fields[key]
            }
            override fun containsKey(key: String): Boolean {
                check(key in allowed) { "Unexpected profile field probe" }
                return fields.containsKey(key)
            }
        }
        assertTrue(LiveMarkerProfile.fromFields(guarded).appearance().showMembershipMapRing)
    }

    @Test fun `preference defaults on but false and malformed preferences fail closed`() {
        assertTrue(MembershipMapRing.fromFields(true, null).visible)
        assertTrue(MembershipMapRing.fromFields(true, true).visible)
        assertFalse(MembershipMapRing.fromFields(true, false).visible)
        for (invalid in listOf("true", 1, emptyMap<String, Any>()))
            assertFalse(MembershipMapRing.fromFields(true, invalid).visible)
    }

    @Test fun `preference write is single flight reports failure and retries without changing entitlement`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<Boolean>()
        val coordinator = MembershipMapRingPreferenceCoordinator {
            writes += it
            if (writes.size == 1) { gate.await(); error("Fixture denial") }
        }
        launch { coordinator.save(false) }; runCurrent()
        coordinator.save(true)
        assertEquals(listOf(false), writes)
        assertEquals(ProfileEditStatus.Saving, coordinator.status.value)
        gate.complete(Unit); runCurrent()
        assertEquals(ProfileEditStatus.Failed, coordinator.status.value)
        coordinator.save(false)
        assertEquals(listOf(false, false), writes)
        assertEquals(ProfileEditStatus.Saved, coordinator.status.value)
    }

    @Test fun `cancellation does not report a failed save`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val coordinator = MembershipMapRingPreferenceCoordinator { gate.await() }
        val job = launch { coordinator.save(false) }; runCurrent()
        job.cancel(); runCurrent()
        assertEquals(ProfileEditStatus.Idle, coordinator.status.value)
    }
}
