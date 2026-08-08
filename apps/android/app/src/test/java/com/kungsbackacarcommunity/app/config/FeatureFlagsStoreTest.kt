package com.kungsbackacarcommunity.app.config

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsStoreTest {

    private class FakeRepo(
        private val result: FeatureFlags? = null,
        private val fail: Boolean = false,
        private val stream: Flow<FeatureFlags> = emptyFlow(),
    ) : FeatureFlagsRepository {
        override suspend fun fetch(): FeatureFlags {
            if (fail) throw IllegalStateException("read failed")
            return result ?: FeatureFlags.DEFAULTS
        }

        override fun observe(): Flow<FeatureFlags> = stream
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

    @Test
    fun `flags start at defaults before the first observe emission`() = runTest {
        // A stream that never emits leaves the store at its initial defaults.
        val store = FeatureFlagsStore(FakeRepo(stream = flow { /* no emission */ }))
        assertFalse(store.flags.value.isEnabled(FeatureFlag.CROWN_HUNT_SPAWN)) // default OFF
        assertTrue(store.flags.value.isEnabled(FeatureFlag.CROWN_HUNT)) // default ON
    }

    @Test
    fun `observe updates the flags live on each emission`() = runTest {
        // The bug's real value: crownHuntSpawn arrives true from the backend.
        val store =
            FeatureFlagsStore(
                FakeRepo(
                    stream =
                        flow {
                            emit(FeatureFlags.fromStored(mapOf("crownHuntSpawn" to true)))
                        },
                ),
            )
        // Before collection the store is on the conservative default (OFF).
        assertFalse(store.flags.value.isEnabled(FeatureFlag.CROWN_HUNT_SPAWN))
        store.observe()
        assertTrue(store.flags.value.isEnabled(FeatureFlag.CROWN_HUNT_SPAWN))
    }

    @Test
    fun `observe keeps the last good value when the stream errors afterwards`() = runTest {
        val store =
            FeatureFlagsStore(
                FakeRepo(
                    stream =
                        flow {
                            emit(FeatureFlags.fromStored(mapOf("crownHuntSpawn" to true)))
                            throw IllegalStateException("listener dropped")
                        },
                ),
            )
        store.observe() // returns after swallowing the error
        // The good value survives; it does NOT revert to the crownHuntSpawn=false default.
        assertTrue(store.flags.value.isEnabled(FeatureFlag.CROWN_HUNT_SPAWN))
    }

    @Test
    fun `null repository observe is a no-op leaving defaults`() = runTest {
        val store = FeatureFlagsStore(null)
        store.observe()
        assertTrue(store.flags.value.isEnabled(FeatureFlag.LIVE_LOCATION))
    }
}
