package com.kungsbackacarcommunity.app.config

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsStoreTest {

    private class FakeRepo(
        private val result: FeatureFlags? = null,
        private val fail: Boolean = false,
    ) : FeatureFlagsRepository {
        override suspend fun fetch(): FeatureFlags {
            if (fail) throw IllegalStateException("read failed")
            return result ?: FeatureFlags.DEFAULTS
        }
    }

    @Test
    fun `refresh replaces the flags on success`() = runTest {
        val store = FeatureFlagsStore(FakeRepo(result = FeatureFlags.fromStored(mapOf("chat" to false))))
        store.refresh()
        assertFalse(store.flags.value.isEnabled(FeatureFlag.CHAT))
    }

    @Test
    fun `refresh keeps the last good flags on failure`() = runTest {
        val store = FeatureFlagsStore(FakeRepo(fail = true))
        store.refresh()
        assertTrue(store.flags.value.isEnabled(FeatureFlag.CHAT)) // still default
    }

    @Test
    fun `null repository refresh is a no-op leaving defaults`() = runTest {
        val store = FeatureFlagsStore(null)
        store.refresh()
        assertTrue(store.flags.value.isEnabled(FeatureFlag.LIVE_LOCATION))
    }
}
