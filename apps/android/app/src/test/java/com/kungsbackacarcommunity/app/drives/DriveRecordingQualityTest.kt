package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRecordingQualityTest {
    private fun sample(
        metres: Double = 0.0,
        elapsed: Long = 1_000,
        receipt: Long = elapsed,
        accuracy: Double? = 5.0,
        longitude: Double = 12.0,
        epoch: Long = 1_800_000_000_000L + elapsed,
    ) = DriveLocationSample(
        latitude = 57.0 + metres * 180.0 / (Math.PI * 6_371_000.0),
        longitude = longitude,
        timestampMs = epoch,
        elapsedRealtimeMs = elapsed,
        receivedElapsedRealtimeMs = receipt,
        receivedAtMillis = 1_800_000_000_000L + receipt,
        accuracyMeters = accuracy,
    )

    @Test
    fun `highway drive retains distance after isolated GPS excursion`() {
        val quality = DriveRecordingQuality()
        val raw = listOf(
            sample(), sample(166.6667, 6_000),
            sample(250.0, 8_500, longitude = 12.025),
            sample(333.3333, 11_000), sample(500.0, 16_000),
        )
        val accepted = raw.mapNotNull { quality.process(it, true) }
        assertEquals(4, accepted.size)
        assertEquals(500.0, DriveSummary.totalDistanceMetres(accepted), 0.01)
        assertEquals(1, quality.diagnostics.rejectedSpeed)
        assertEquals(5, quality.diagnostics.receivedPoints)
        assertEquals(4, quality.diagnostics.acceptedPoints)
    }

    @Test
    fun `repeated GPS excursions cannot discard most of a healthy 57 kilometre drive`() {
        val quality = DriveRecordingQuality()
        // Synthetic straight highway travel: 57 km at 120 km/h, 5 s samples.
        // Repeated lateral excursions in the middle poison both adjacent raw
        // segments. Clean fixes on either side still describe the whole drive.
        val samples = (0..342).map { index ->
            sample(
                metres = 57_000.0 * index / 342,
                elapsed = 1_000L + index * 5_000L,
                longitude = if (index in 48..294 && index % 2 == 0) 12.025 else 12.0,
            )
        }
        val rawPoints = samples.map { RecordedPoint(it.latitude, it.longitude, it.timestampMs) }
        val accepted = samples.mapNotNull { quality.process(it, true) }
        assertEquals(15_666.6667, DriveSummary.totalDistanceMetres(rawPoints), 0.01)
        assertEquals(57_000.0, DriveSummary.totalDistanceMetres(accepted), 0.01)
        assertEquals(rawPoints.first(), accepted.first())
        assertEquals(rawPoints.last(), accepted.last())
        assertEquals(124, quality.diagnostics.rejectedSpeed)
        assertEquals(samples.size - 124, quality.diagnostics.acceptedPoints)
    }

    @Test
    fun `coarse stale invalid and duplicate samples are counted without poisoning anchor`() {
        val quality = DriveRecordingQuality()
        assertNotNull(quality.process(sample(), true))
        assertNull(quality.process(sample(5.0, 2_000, accuracy = 500.0), true))
        assertNull(quality.process(sample(5.0, 2_000, accuracy = null), true))
        assertNull(quality.process(sample(5.0, 2_000, receipt = 20_000), true))
        assertNull(quality.process(sample().copy(latitude = Double.NaN), true))
        assertNull(quality.process(sample(), true))
        assertNotNull(quality.process(sample(100.0, 6_000), true))
        assertEquals(2, quality.diagnostics.rejectedAccuracy)
        assertEquals(1, quality.diagnostics.rejectedStale)
        assertEquals(1, quality.diagnostics.rejectedInvalid)
        assertEquals(1, quality.diagnostics.rejectedTime)
        assertEquals(2, quality.diagnostics.acceptedPoints)
    }

    @Test
    fun `provider wall clock changes do not alter route intervals`() {
        val quality = DriveRecordingQuality()
        val first = quality.process(sample(), true)!!
        val next = quality.process(sample(100.0, 6_000, epoch = 1), true)!!
        assertEquals(5_000L, next.timestampMs - first.timestampMs)
    }

    @Test
    fun `slow motion is preserved without deadband`() {
        val quality = DriveRecordingQuality()
        val points = (0..20).mapNotNull {
            quality.process(sample(it.toDouble(), 1_000L + it * 5_000L), true)
        }
        assertEquals(21, points.size)
        assertEquals(20.0, DriveSummary.totalDistanceMetres(points), 0.01)
        assertEquals(0, quality.diagnostics.droppedStationary)
    }

    @Test
    fun `raw receipt silence is measured before quality decisions and survives bounded counters`() {
        val quality = DriveRecordingQuality()
        quality.process(sample(), true)
        quality.process(sample(accuracy = 500.0, elapsed = 21_000), true)
        quality.process(sample(elapsed = 22_000), false)
        assertEquals(20_000L, quality.diagnostics.maxGapMs)
        assertEquals(1, quality.diagnostics.gapCount)
        assertEquals(1, quality.diagnostics.droppedCapacity)
        assertEquals(3, quality.diagnostics.receivedPoints)
        assertEquals(DriveRecordingDiagnostics.MAX_COUNT, DriveRecordingQuality.increment(DriveRecordingDiagnostics.MAX_COUNT))
    }

    @Test
    fun `restoration resets boot clock and refuses overlapping epoch instead of inventing travel time`() {
        val initial = DriveRecordingDiagnostics(receivedPoints = 10, acceptedPoints = 8, rejectedAccuracy = 2, resumedCount = 1)
        val quality = DriveRecordingQuality(initial, RecordedPoint(57.0, 12.0, 1_800_000_100_000))
        assertNull(quality.process(sample(elapsed = 1_000), true))
        assertNotNull(quality.process(sample(elapsed = 101_000), true))
        assertEquals(0, quality.diagnostics.rejectedSpeed)
        assertEquals(1, quality.diagnostics.rejectedTime)
        assertEquals(9, quality.diagnostics.acceptedPoints)
        assertTrue(quality.diagnostics.toWire().values.none { it is Double })
    }

    @Test
    fun `short post-restore teleport is withheld until a trustworthy fix returns`() {
        val restored = RecordedPoint(57.0, 12.0, 1_800_000_000_000)
        val quality = DriveRecordingQuality(restoredLastPoint = restored)
        assertNull(quality.process(sample(elapsed = 5_000, longitude = 12.025), true))
        assertNotNull(quality.process(sample(100.0, 10_000), true))
        assertEquals(1, quality.diagnostics.rejectedSpeed)
        assertEquals(1, quality.diagnostics.acceptedPoints)
    }

    @Test
    fun `bounded journal checkpoint rejects corrupt values and carries no coordinates`() {
        val diagnostics = DriveRecordingDiagnostics(receivedPoints = 6, acceptedPoints = 3, rejectedAccuracy = 2, rejectedSpeed = 1, resumedCount = 1)
        assertEquals(diagnostics, DriveRecordingDiagnostics.fromJournalLine(diagnostics.journalLine()))
        assertNull(DriveRecordingDiagnostics.fromJournalLine(diagnostics.journalLine() + ",unknown"))
        assertNull(DriveRecordingDiagnostics.fromJournalLine(diagnostics.journalLine().replace("D,1,6", "D,1,-1")))
        assertEquals("recording-quality-v1", diagnostics.toWire()["algorithmVersion"])
        assertEquals(15, diagnostics.toWire().size)
    }
}
