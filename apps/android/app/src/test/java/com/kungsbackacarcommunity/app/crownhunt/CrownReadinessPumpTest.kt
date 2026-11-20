package com.kungsbackacarcommunity.app.crownhunt

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CrownReadinessPumpTest {
    private val target = CrownSpawn("crown", 57.5, 12.0, CrownRarity.COMMON, 10, 75.0, null)
    private fun fix(at: Long) = CrownFix(57.5, 12.0, at, 0.0, 5.0)

    @Test
    fun stalledProviderDoesNotBlockExpiryAndCancellationReleasesItsDemand() = runTest {
        val session = CrownReadinessSession()
        session.record(fix(0)); session.record(fix(4000))
        val fixes = Channel<CrownFix>(Channel.UNLIMITED)
        var demand = 0
        var latest = CrownReadinessSnapshot()
        val task = backgroundScope.launch {
            runCrownReadiness(session, true, { target }, { listOf(target) },
                { 4000 + testScheduler.currentTime },
                { flow { demand++; try { emitAll(fixes.receiveAsFlow()) } finally { demand-- } } },
                { latest = it })
        }
        runCurrent()
        assertEquals(1, demand)
        assertNotNull(latest.previous)
        advanceTimeBy(2_251); runCurrent()
        assertNull(latest.current)
        task.cancelAndJoin()
        assertEquals(0, demand)
    }

    @Test
    fun readyPrewarmReleasesGpsAndRenewsBeforeTheProofExpires() = runTest {
        val session = CrownReadinessSession()
        session.record(fix(0)); session.record(fix(4000))
        val fixes = Channel<CrownFix>(Channel.UNLIMITED)
        var demand = 0
        val task = backgroundScope.launch {
            runCrownReadiness(session, false, { null }, { listOf(target) },
                { 4000 + testScheduler.currentTime },
                { flow { demand++; try { emitAll(fixes.receiveAsFlow()) } finally { demand-- } } }, {})
        }
        runCurrent()
        assertEquals(0, demand)
        advanceTimeBy(8000); runCurrent()
        assertEquals(1, demand)
        fixes.send(fix(12000)); runCurrent()
        assertEquals(0, demand)
        assertNotNull(session.proofForSubmission(12000, target))
        task.cancelAndJoin()
        assertEquals(0, demand)
    }

    @Test
    fun noGpsPrewarmHasAnEightSecondBurstAndARealOffInterval() = runTest {
        val fixes = Channel<CrownFix>(Channel.UNLIMITED)
        var demand = 0
        val task = backgroundScope.launch {
            runCrownReadiness(CrownReadinessSession(), false, { null }, { listOf(target) },
                { testScheduler.currentTime },
                { flow { demand++; try { emitAll(fixes.receiveAsFlow()) } finally { demand-- } } }, {})
        }
        runCurrent(); assertEquals(1, demand)
        advanceTimeBy(8000); runCurrent(); assertEquals(0, demand)
        advanceTimeBy(3000); runCurrent(); assertEquals(1, demand)
        task.cancelAndJoin(); assertEquals(0, demand)
    }
}
