package com.kungsbackacarcommunity.app.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SupporterBadgeTest {
    @Test fun `only eligible profiles display crown and missing preference defaults on`() {
        assertFalse(SupporterBadge.fromFields(null, null).visible)
        assertFalse(SupporterBadge.fromFields("true", true).visible)
        assertFalse(SupporterBadge.fromFields(true, "true").visible)
        assertTrue(SupporterBadge.fromFields(true, null).visible)
        assertFalse(SupporterBadge.fromFields(true, false).visible)
        assertTrue(SupporterBadge.fromFields(true, true).visible)
    }

    @Test fun `saved choice survives repository recreation lapse and reactivation`() = runTest {
        var persisted = true
        val first = SupporterBadgePreferenceCoordinator { persisted = it }
        first.save(false)
        assertEquals(ProfileEditStatus.Saved, first.status.value)
        assertFalse(SupporterBadge.fromFields(false, persisted).visible)
        assertFalse(SupporterBadge.fromFields(true, persisted).visible)
        val second = SupporterBadgePreferenceCoordinator { persisted = it }
        second.save(true)
        assertTrue(SupporterBadge.fromFields(true, persisted).visible)
    }

    @Test fun `duplicate saves ignored failure reported and retry works`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = SupporterBadgePreferenceCoordinator {
            calls++
            if (calls == 1) { gate.await(); error("rejected") }
        }
        launch { coordinator.save(false) }
        runCurrent()
        coordinator.save(true)
        assertEquals(1, calls)
        assertEquals(ProfileEditStatus.Saving, coordinator.status.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(ProfileEditStatus.Failed, coordinator.status.value)
        coordinator.save(false)
        assertEquals(ProfileEditStatus.Saved, coordinator.status.value)
    }
}
