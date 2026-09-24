package com.kungsbackacarcommunity.app.live

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestMarkerObservationRetryTest {

    @Test
    fun `retries only after explicit retry signal`() = runTest {
        var calls = 0
        val resumed = MutableSharedFlow<LatestMarkerObservation<Int>>(replay = 1)

        val values =
            async {
                recoverLatestMarkerFlow {
                    calls += 1
                    if (calls == 1) {
                        flowOf(
                            LatestMarkerObservation.Value(1),
                            LatestMarkerObservation.Retry,
                        )
                    } else {
                        resumed
                    }
                }.take(2).toList()
            }

        runCurrent()
        delay(1_050)
        resumed.tryEmit(LatestMarkerObservation.Value(2))

        assertEquals(listOf(1, 2), values.await())
        assertEquals(2, calls)
    }

    @Test
    fun `normal completion does not resubscribe`() = runTest {
        var calls = 0

        val values =
            recoverLatestMarkerFlow {
                calls += 1
                flowOf(LatestMarkerObservation.Value(7))
            }.toList()

        assertEquals(listOf(7), values)
        assertEquals(1, calls)
    }
}
