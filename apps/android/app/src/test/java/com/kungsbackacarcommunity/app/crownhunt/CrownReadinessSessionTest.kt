package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.*
import org.junit.Test

class CrownReadinessSessionTest {
    private val epoch = 1_800_000_000_000L
    private val crown = CrownSpawn("crown", 57.5, 12.0, CrownRarity.COMMON, 10, 75.0, null)
    private fun fix(ms: Long, accuracy: Double = 5.0, north: Double = 0.0, speed: Double? = 0.0) =
        CrownFix(57.5 + north / 111195.0, 12.0, epoch + ms, speed, accuracy)

    @Test
    fun stoppedBeforeOpeningIsImmediatelyReadyWithoutAnotherLocationRead() {
        val session = CrownReadinessSession()
        (0L..6000L step 1000L).forEach { session.record(fix(it)) }
        assertNotNull(session.proofForSubmission(epoch + 6500, crown))
        assertEquals(epoch + 6000, session.proofForSubmission(epoch + 6500, crown)!!.current.recordedAtMillis)
    }

    @Test
    fun differentCallbackPhasesUnlockOnTheFirstFourSecondPhysicalPair() {
        for (phase in listOf(0L, 125L, 900L)) {
            val session = CrownReadinessSession()
            for (second in 0..4) {
                val at = phase + second * 1000L
                session.record(fix(at))
                if (second < 4) assertNull(session.proofForSubmission(epoch + at, crown))
                else assertNotNull(session.proofForSubmission(epoch + at, crown))
            }
        }
    }

    @Test
    fun improvingAccuracyUsesTheWarmPartnerImmediately() {
        val session = CrownReadinessSession()
        (0L..4000L step 1000L).forEach { session.record(fix(it, accuracy = 100.0)) }
        assertNull(session.proofForSubmission(epoch + 4000, crown))
        session.record(fix(5000, accuracy = 5.0))
        assertNotNull(session.proofForSubmission(epoch + 5000, crown))
    }

    @Test
    fun repeatedCachedSamplesAndClockTicksNeverCreateEvidenceOrRefreshItsTimestamp() {
        val session = CrownReadinessSession()
        session.record(fix(0))
        (1..20).forEach { poll ->
            session.record(fix(0))
            assertNull(session.proofForSubmission(epoch + poll * 1000L, crown))
        }
        val empty = CrownReadinessSession()
        assertNull(empty.proofForSubmission(epoch + 4000, crown))
    }

    @Test
    fun clockExpiresEvidenceEvenWhenTheProviderDoesNotReply() {
        val session = CrownReadinessSession()
        session.record(fix(0)); session.record(fix(4000))
        assertNotNull(session.proofForSubmission(epoch + 6000, crown))
        assertNull(session.proofForSubmission(epoch + 6001, crown))
        assertNull(session.snapshot(epoch + 6001, crown).current)
    }

    @Test
    fun prewarmPausesThenRenewsBeforeExpiryAndLifecycleClearDropsEvidence() {
        val session = CrownReadinessSession()
        session.record(fix(0)); session.record(fix(4000))
        assertEquals(8000L, session.prewarmPauseMillis(epoch + 4000, listOf(crown)))
        assertEquals(0L, session.prewarmPauseMillis(epoch + 12000, listOf(crown)))
        session.record(fix(12000))
        assertNotNull(session.proofForSubmission(epoch + 12000, crown))
        session.clear()
        assertNull(session.proofForSubmission(epoch + 12000, crown))
    }

    @Test
    fun oneAccurateButMovingPartnerCannotHideAnotherUsableStoppedPair() {
        val session = CrownReadinessSession()
        session.record(fix(0, accuracy = 1.0, speed = 6.0))
        session.record(fix(2000, accuracy = 5.0))
        session.record(fix(4000))
        session.record(fix(6000, speed = 0.6))
        val pair = session.proofForSubmission(epoch + 6000, crown)!!
        assertEquals(epoch + 6000, pair.current.recordedAtMillis)
        assertEquals(epoch + 2000, pair.previous.recordedAtMillis)
    }

    @Test
    fun departureCannotReuseTheAlreadyReadyStop() {
        val session = CrownReadinessSession()
        session.record(fix(0)); session.record(fix(4000))
        assertNotNull(session.proofForSubmission(epoch + 4000, crown))
        session.record(fix(6000, north = 2.0, speed = 1.0))
        assertNull(session.proofForSubmission(epoch + 6000, crown))
    }

    @Test
    fun openingAfterAPrewarmPauseRequiresOneFreshPhysicalFixWithoutRestartingDwell() {
        val session = CrownReadinessSession()
        session.record(fix(0)); session.record(fix(4000))
        assertNull("cached parked proof must not enable a tap after a pause", session.proofForSubmission(epoch + 8000, crown))
        session.record(fix(8000))
        assertNotNull("one fresh stationary fix reuses the earned four-second history", session.proofForSubmission(epoch + 8000, crown))
    }

    @Test
    fun departureDuringThePrewarmPauseCannotSubmitTheOldParkedProof() {
        for (speed in listOf(1.0, 0.0, null)) {
            val session = CrownReadinessSession()
            session.record(fix(0)); session.record(fix(4000))
            assertNull(session.proofForSubmission(epoch + 8000, crown))
            session.record(fix(8000, north = 4.0, speed = speed))
            assertNull("departed with speed=$speed", session.proofForSubmission(epoch + 8000, crown))
        }
    }
}
