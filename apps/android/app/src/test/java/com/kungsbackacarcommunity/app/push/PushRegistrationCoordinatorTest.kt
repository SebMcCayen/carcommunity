package com.kungsbackacarcommunity.app.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRegistrationCoordinatorTest {

    private class FakeRepo : PushTokenRepository {
        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()
        var failWith: Exception? = null

        override suspend fun register(token: String) {
            failWith?.let { throw it }
            registered += token
        }

        override suspend fun unregister(token: String) {
            failWith?.let { throw it }
            unregistered += token
        }
    }

    @Test
    fun `register success lands in Registered`() = runTest {
        val repo = FakeRepo()
        val coordinator = PushRegistrationCoordinator(repo) { "fcm-token" }
        coordinator.registerCurrentToken()
        assertEquals(listOf("fcm-token"), repo.registered)
        assertEquals(PushRegistrationStatus.Registered, coordinator.status.value)
    }

    @Test
    fun `missing token lands in Failed without calling the backend`() = runTest {
        val repo = FakeRepo()
        val coordinator = PushRegistrationCoordinator(repo) { null }
        coordinator.registerCurrentToken()
        assertTrue(repo.registered.isEmpty())
        assertEquals(PushRegistrationStatus.Failed, coordinator.status.value)
    }

    @Test
    fun `register failure lands in Failed`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("boom") }
        val coordinator = PushRegistrationCoordinator(repo) { "fcm-token" }
        coordinator.registerCurrentToken()
        assertEquals(PushRegistrationStatus.Failed, coordinator.status.value)
    }

    @Test
    fun `register cancellation is rethrown and leaves Idle`() = runTest {
        val repo = FakeRepo().apply { failWith = CancellationException("c") }
        val coordinator = PushRegistrationCoordinator(repo) { "fcm-token" }
        var rethrown = false
        try {
            coordinator.registerCurrentToken()
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(PushRegistrationStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `unregister success calls through and returns to Idle`() = runTest {
        val repo = FakeRepo()
        val coordinator = PushRegistrationCoordinator(repo) { "fcm-token" }
        coordinator.unregisterCurrentToken()
        assertEquals(listOf("fcm-token"), repo.unregistered)
        assertEquals(PushRegistrationStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `unregister with no token is a no-op ending Idle`() = runTest {
        val repo = FakeRepo()
        val coordinator = PushRegistrationCoordinator(repo) { null }
        coordinator.unregisterCurrentToken()
        assertTrue(repo.unregistered.isEmpty())
        assertEquals(PushRegistrationStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `unregister failure lands in Failed`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("boom") }
        val coordinator = PushRegistrationCoordinator(repo) { "fcm-token" }
        coordinator.unregisterCurrentToken()
        assertEquals(PushRegistrationStatus.Failed, coordinator.status.value)
    }

    @Test
    fun `tokenId is the SHA-256 hex of the raw token`() {
        // echo -n "fcm-token" | sha256sum
        assertEquals(
            "f0bba75fabb9b2f6b5046edb4ccf796453b41f66892f8d03f40be27e99f90ce4",
            PushTokens.tokenId("fcm-token"),
        )
        // 64 lowercase hex chars — matches the backend unregister input schema.
        assertTrue(PushTokens.tokenId("any").matches(Regex("^[a-f0-9]{64}$")))
    }
}
