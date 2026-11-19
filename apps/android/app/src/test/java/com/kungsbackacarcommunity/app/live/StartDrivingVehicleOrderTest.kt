package com.kungsbackacarcommunity.app.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartDrivingVehicleOrderTest {
    @Test
    fun refreshKeepsCachedOrderVisibleWhileWaitingAndAfterFailure() = runTest {
        val response = CompletableDeferred<List<String>>()
        var calls = 0
        val order = StartDrivingVehicleOrder {
            when (++calls) {
                1 -> listOf("recent", "older", "recent", "")
                2 -> response.await()
                else -> error("Offline")
            }
        }
        order.refresh()
        assertEquals(listOf("recent", "older"), order.ids.value)
        val refresh = launch { order.refresh() }
        runCurrent()
        assertEquals(listOf("recent", "older"), order.ids.value)
        response.complete(listOf("newest", "recent"))
        refresh.join()
        order.refresh()
        assertEquals(listOf("newest", "recent"), order.ids.value)
    }

    @Test
    fun dismissCancelsPendingRefreshWithoutPublishingLateResults() = runTest {
        val response = CompletableDeferred<List<String>>()
        val order = StartDrivingVehicleOrder { response.await() }
        var returnedNormally = false
        val refresh = launch { order.refresh(); returnedNormally = true }
        runCurrent()
        refresh.cancel()
        refresh.join()
        response.complete(listOf("old-account-car"))
        assertTrue(refresh.isCancelled)
        assertTrue(order.ids.value.isEmpty())
        assertFalse(returnedNormally)
    }
}
