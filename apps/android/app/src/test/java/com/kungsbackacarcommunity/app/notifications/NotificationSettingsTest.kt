package com.kungsbackacarcommunity.app.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSettingsTest {

    @Test
    fun `missing categories default to fully enabled`() {
        val prefs = NotificationPreferences.ALL_ENABLED
        assertEquals(CategoryPreference(inApp = true, push = true), prefs.effective("event_reminder"))
    }

    @Test
    fun `toggle updates a non-essential category channel`() {
        val prefs =
            NotificationPreferences.ALL_ENABLED
                .withToggle("event_reminder", NotificationChannel.PUSH, false)
        assertFalse(prefs.effective("event_reminder").push)
        assertTrue(prefs.effective("event_reminder").inApp)
    }

    @Test
    fun `essential categories always read enabled and reject toggles`() {
        val prefs =
            NotificationPreferences.ALL_ENABLED
                .withToggle("account_warning", NotificationChannel.PUSH, false)
                .withToggle("account_suspension", NotificationChannel.IN_APP, false)
        assertEquals(CategoryPreference(true, true), prefs.effective("account_warning"))
        assertEquals(CategoryPreference(true, true), prefs.effective("account_suspension"))
        // Essential categories are never persisted.
        assertFalse(prefs.toFirestoreMap().containsKey("account_warning"))
    }

    @Test
    fun `toFirestoreMap serializes only non-essential overrides`() {
        val prefs =
            NotificationPreferences.ALL_ENABLED
                .withToggle("system_notice", NotificationChannel.IN_APP, false)
        val map = prefs.toFirestoreMap()
        assertEquals(mapOf("inApp" to false, "push" to true), map["system_notice"])
        assertFalse(map.containsKey("account_warning"))
    }

    @Test
    fun `fromFirestore round-trips channel opt-outs and defaults missing fields`() {
        val raw =
            mapOf<String, Any?>(
                "event_updated" to mapOf("inApp" to false, "push" to true),
                "system_notice" to mapOf("push" to false), // inApp missing → default enabled
                "bogus" to "not-a-map", // ignored
            )
        val prefs = NotificationPreferences.fromFirestore(raw)
        assertEquals(CategoryPreference(inApp = false, push = true), prefs.effective("event_updated"))
        assertEquals(CategoryPreference(inApp = true, push = false), prefs.effective("system_notice"))
        // Unknown/malformed entries fall back to enabled defaults.
        assertEquals(CategoryPreference(true, true), prefs.effective("admin_message"))
    }

    @Test
    fun `fromFirestore null yields all-enabled`() {
        assertEquals(
            CategoryPreference(true, true),
            NotificationPreferences.fromFirestore(null).effective("event_reminder"),
        )
    }

    @Test
    fun `coordinator marks saved on success`() = runTest {
        val coordinator = NotificationSettingsCoordinator(FakeRepo(shouldFail = false))
        coordinator.save("u1", NotificationPreferences.ALL_ENABLED)
        assertEquals(NotificationSettingsSaveStatus.Saved, coordinator.saveStatus.value)
    }

    @Test
    fun `coordinator marks failed when the write throws`() = runTest {
        val coordinator = NotificationSettingsCoordinator(FakeRepo(shouldFail = true))
        coordinator.save("u1", NotificationPreferences.ALL_ENABLED)
        assertEquals(NotificationSettingsSaveStatus.Failed, coordinator.saveStatus.value)
    }
}

private class FakeRepo(private val shouldFail: Boolean) : NotificationSettingsRepository {
    override fun observePreferences(uid: String): Flow<NotificationSettingsState> =
        flowOf(NotificationSettingsState.Loaded(NotificationPreferences.ALL_ENABLED))

    override suspend fun savePreferences(uid: String, preferences: NotificationPreferences) {
        if (shouldFail) throw IllegalStateException("write failed")
    }
}
