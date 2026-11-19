package com.kungsbackacarcommunity.app.shell

import android.animation.ValueAnimator
import com.mapbox.common.location.LocationError
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.locationcomponent.LocationConsumer
import com.mapbox.maps.plugin.locationcomponent.LocationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPuckLocationProviderTest {
    private var now = 10_000L
    private val device = DeviceProvider()
    private val provider = RecordingPuckLocationProvider(device) { now }
    private val consumer = Consumer()

    private fun position(longitude: Double = 12.08, latitude: Double = 57.48) = MapRecordingPosition(
        point = MapPoint(longitude, latitude),
        elapsedRealtimeMs = now,
        accuracyMeters = 5.0,
        bearingDegrees = 90.0,
        speedMps = 10.0,
    )

    @Test
    fun `camera source identity advances on raw receipts but never on display frames`() {
        provider.registerLocationConsumer(consumer)
        val proxy = device.consumers.single()
        val point = Point.fromLngLat(12.0, 57.0)
        proxy.onLocationUpdated(point)
        val first = requireNotNull(provider.cameraSourceFix)
        assertEquals(MapPoint(12.0, 57.0), first.point)
        assertEquals(null, first.capturedAtMs) // The SDK supplies Point[], not a GPS timestamp.
        now += 1_000
        proxy.onLocationUpdated(point)
        val identical = requireNotNull(provider.cameraSourceFix)
        assertTrue(identical.sequence > first.sequence)
        assertEquals(first.point, identical.point)

        val fix = position().copy(pointCount = 1)
        provider.update(true, fix, listOf(fix.point))
        val recordingSource = requireNotNull(provider.cameraSourceFix)
        assertEquals(fix.elapsedRealtimeMs, recordingSource.capturedAtMs)
        now += 100
        provider.advance()
        assertEquals(recordingSource, provider.cameraSourceFix)
    }

    @Test
    fun `live puck uses exact recorder vertices and fences independent device fixes`() {
        provider.registerLocationConsumer(consumer)
        val oldDeviceConsumer = device.consumers.single()
        oldDeviceConsumer.onLocationUpdated(Point.fromLngLat(12.0, 57.0))
        assertEquals(1, consumer.points.size)

        val first = position()
        provider.update(recording = true, first)
        assertTrue(device.consumers.isEmpty())
        oldDeviceConsumer.onLocationUpdated(Point.fromLngLat(13.0, 58.0))
        oldDeviceConsumer.onBearingUpdated(270.0)
        oldDeviceConsumer.onHorizontalAccuracyRadiusUpdated(500.0)
        assertEquals(Point.fromLngLat(first.point.longitude, first.point.latitude), consumer.points.last())
        assertEquals(listOf(90.0), consumer.bearings)
        assertEquals(listOf(5.0), consumer.accuracies)
        assertEquals(listOf("bearing", "position"), consumer.events.takeLast(2))
        assertTrue(consumer.immediateBearing)

        now += 2_000L
        val bend = position(longitude = 12.081, latitude = 57.481)
        provider.update(recording = true, bend)
        assertEquals(Point.fromLngLat(bend.point.longitude, bend.point.latitude), consumer.points.last())
        assertTrue(consumer.immediatePosition)
        provider.update(recording = true, bend)
        assertEquals(3, consumer.points.size) // Timer/recomposition must not restart animation.
    }

    @Test
    fun `recording waits for a fresh fix and expires without falling back to device location`() {
        provider.update(recording = true, position = null)
        provider.registerLocationConsumer(consumer)
        assertFalse(provider.canDisplayLocation)
        assertTrue(consumer.points.isEmpty())
        assertTrue(device.consumers.isEmpty())

        val fix = position()
        provider.update(recording = true, fix)
        assertTrue(provider.canDisplayLocation)
        now += RecordingPuckLocationProvider.MAX_FIX_AGE_MS + 1
        provider.update(recording = true, fix)
        assertFalse(provider.canDisplayLocation)
        assertTrue(device.consumers.isEmpty())
        val recreatedMap = Consumer()
        provider.registerLocationConsumer(recreatedMap)
        assertTrue(recreatedMap.points.isEmpty())

        provider.update(recording = true, position())
        assertTrue(provider.canDisplayLocation)
        assertEquals(1, recreatedMap.points.size)
    }

    @Test
    fun `map recreation replays only fresh recorder position and unregisters released map`() {
        val fix = position()
        provider.update(recording = true, fix)
        provider.registerLocationConsumer(consumer)
        provider.unRegisterLocationConsumer(consumer)
        val nextMap = Consumer()
        provider.registerLocationConsumer(nextMap)
        assertEquals(consumer.points, nextMap.points)
        now += 2_000L
        provider.update(recording = true, position(longitude = 12.082))
        assertEquals(1, consumer.points.size)
        assertEquals(2, nextMap.points.size)
    }

    @Test
    fun `session end restores device tracking and callbacks from old registrations stay fenced`() {
        provider.registerLocationConsumer(consumer)
        val old = device.consumers.single()
        provider.update(recording = true, position())
        provider.update(recording = false, position = null)
        assertTrue(provider.canDisplayLocation)
        val resumed = device.consumers.single()
        old.onLocationUpdated(Point.fromLngLat(0.0, 0.0))
        assertEquals(1, consumer.points.size)
        resumed.onLocationUpdated(Point.fromLngLat(12.09, 57.49))
        assertEquals(Point.fromLngLat(12.09, 57.49), consumer.points.last())
        provider.update(recording = true, position = null) // Next session has no inherited fix.
        resumed.onLocationUpdated(Point.fromLngLat(0.0, 0.0))
        assertFalse(provider.canDisplayLocation)
        assertEquals(2, consumer.points.size)
    }

    @Test
    fun `missing bearing does not invent north and future boot timestamps are not replayed`() {
        provider.update(recording = true, position().copy(bearingDegrees = null))
        provider.registerLocationConsumer(consumer)
        assertTrue(consumer.bearings.isEmpty())
        provider.update(recording = true, position().copy(elapsedRealtimeMs = now + 1))
        assertFalse(provider.canDisplayLocation)
    }

    @Test
    fun `style reinitialization can register the same consumer and replay the current fix`() {
        provider.update(recording = true, position())
        provider.registerLocationConsumer(consumer)
        provider.registerLocationConsumer(consumer)
        assertEquals(2, consumer.points.size)
        provider.unRegisterLocationConsumer(consumer)
        provider.update(recording = true, position(longitude = 12.09))
        assertEquals(2, consumer.points.size)
        provider.update(recording = false, position = null)
        assertTrue(device.consumers.isEmpty())
    }

    @Test
    fun `missing course during straight driving recovers from accepted movement and replays on recreation`() {
        provider.registerLocationConsumer(consumer)
        provider.update(recording = true, position().copy(bearingDegrees = 0.0))
        now += 2_000L
        provider.update(recording = true, position(longitude = 12.0805).copy(bearingDegrees = null))
        assertEquals(90.0, consumer.bearings.last(), 0.01)
        assertEquals(listOf("bearing", "position"), consumer.events.takeLast(2))

        val recreatedMap = Consumer()
        provider.registerLocationConsumer(recreatedMap)
        assertEquals(consumer.bearings.last(), recreatedMap.bearings.single(), 0.0)

        provider.update(recording = false, position = null)
        provider.update(recording = true, position().copy(bearingDegrees = null))
        val nextSession = Consumer()
        provider.registerLocationConsumer(nextSession)
        assertTrue(nextSession.bearings.isEmpty())
    }

    @Test
    fun `presentation frames move smoothly and expose the matching trail before native callbacks`() {
        val first = position().copy(pointCount = 1)
        val last = first.copy(
            point = MapPoint(first.point.longitude + 0.0004, first.point.latitude),
            elapsedRealtimeMs = now + 2_000L,
            pointCount = 2,
        )
        val observedTips = mutableListOf<MapPoint?>()
        val callbackConsumer = Consumer { observedTips.add(provider.renderedFrame?.trail?.lastOrNull()) }
        provider.registerLocationConsumer(callbackConsumer)
        provider.update(true, first, listOf(first.point))
        now += 2_000L
        assertTrue(provider.advance()) // Continuous motion before the next physical fix.
        val before = callbackConsumer.points.last()
        provider.update(true, last, listOf(first.point, last.point))
        assertEquals(before, callbackConsumer.points.last()) // Retarget starts where it was displayed.
        now += 125L
        assertTrue(provider.advance())
        val halfway = callbackConsumer.points.last()
        assertEquals((before.longitude() + last.point.longitude) / 2, halfway.longitude(), 0.000001)
        assertEquals(MapPoint(halfway.longitude(), halfway.latitude()), observedTips.last())
        assertTrue(callbackConsumer.immediatePosition) // Only the shared timeline animates.
        now += 125L
        assertTrue(provider.advance()) // The short correction finishes, prediction continues.
        assertEquals(last.point, observedTips.last())
        now += 3_500L
        assertFalse(provider.advance()) // Prediction ends 3.5 s after source time (+250 ms presentation).
        assertEquals(2, callbackConsumer.accuracies.size)
        val emitted = callbackConsumer.points.size
        now += 5_000L
        assertFalse(provider.advance())
        assertEquals(emitted, callbackConsumer.points.size)
    }

    @Test
    fun `timer updates and native re-registration do not restart presentation motion`() {
        val first = position().copy(pointCount = 1)
        provider.update(true, first, listOf(first.point))
        provider.registerLocationConsumer(consumer)
        now += 2_000L
        val next = position(longitude = first.point.longitude + 0.0004).copy(pointCount = 2)
        val route = listOf(first.point, next.point)
        provider.update(true, next, route)
        now += 1_000L
        provider.advance()
        provider.update(true, next, route)
        val reattached = Consumer()
        provider.registerLocationConsumer(reattached)
        assertEquals(consumer.points.last(), reattached.points.last())
        now += 1_000L
        assertTrue(provider.advance())
        now += 2_000L
        assertFalse(provider.advance())
        val frozen = reattached.points.last()
        now += 1_000L
        assertFalse(provider.advance())
        assertEquals(frozen, reattached.points.last())

        provider.update(false, null)
        val count = reattached.points.size
        now += 1_000L
        assertFalse(provider.advance())
        assertEquals(count, reattached.points.size)
    }

    @Test
    fun `a recreated map starts with the whole accepted prefix and its current fresh head`() {
        val route = listOf(
            MapPoint(12.08, 57.48),
            MapPoint(12.08, 57.4801),
            MapPoint(12.0802, 57.4801),
        )
        val fix = position().copy(point = route.last(), pointCount = 3)
        provider.update(true, fix, route)
        provider.registerLocationConsumer(consumer)
        assertEquals(route, provider.renderedFrame?.trail)
        assertEquals(fix.point.longitude, consumer.points.single().longitude(), 0.0)
        assertTrue(provider.advance()) // A fresh moving source may continue between fixes.
    }

    @Test
    fun `unregistering during a bearing callback fences the remaining recording callbacks`() {
        provider.update(true, position())
        lateinit var released: Consumer
        released = Consumer(onBearing = { provider.unRegisterLocationConsumer(released) })
        provider.registerLocationConsumer(released)
        assertTrue(released.points.isEmpty())
        assertTrue(released.accuracies.isEmpty())
    }

    @Test
    fun `ending recording during a bearing callback fences the old recording position`() {
        provider.update(true, position())
        val stopped = Consumer(onBearing = { provider.update(false, null) })
        provider.registerLocationConsumer(stopped)
        assertTrue(stopped.points.isEmpty())
        assertTrue(stopped.accuracies.isEmpty())
        assertEquals(1, device.consumers.size)
    }

    @Test
    fun `a reentrant newer fix cannot be followed by an older frame for either consumer`() {
        var replace = true
        val newer = position(longitude = 12.082).copy(elapsedRealtimeMs = now + 2_000L)
        val replacing = Consumer(onBearing = {
            if (replace) {
                replace = false
                now += 2_000L
                provider.update(true, newer)
            }
        })
        provider.registerLocationConsumer(replacing)
        provider.registerLocationConsumer(consumer)
        provider.update(true, position())
        val expected = listOf(Point.fromLngLat(newer.point.longitude, newer.point.latitude))
        assertEquals(expected, replacing.points)
        assertEquals(expected, consumer.points)
    }

    private class DeviceProvider : LocationProvider {
        val consumers = mutableSetOf<LocationConsumer>()
        override fun registerLocationConsumer(locationConsumer: LocationConsumer) { consumers.add(locationConsumer) }
        override fun unRegisterLocationConsumer(locationConsumer: LocationConsumer) { consumers.remove(locationConsumer) }
    }

    private class Consumer(
        private val onBearing: (() -> Unit)? = null,
        private val onPosition: () -> Unit = {},
    ) : LocationConsumer {
        val points = mutableListOf<Point>()
        val bearings = mutableListOf<Double>()
        val accuracies = mutableListOf<Double>()
        val events = mutableListOf<String>()
        var immediatePosition = false
        var immediateBearing = false
        override fun onLocationUpdated(vararg location: Point, options: (ValueAnimator.() -> Unit)?) {
            events.add("position")
            points.addAll(location)
            immediatePosition = options != null
            onPosition()
        }
        override fun onBearingUpdated(vararg bearing: Double, options: (ValueAnimator.() -> Unit)?) {
            events.add("bearing")
            bearings.addAll(bearing.toList())
            immediateBearing = options != null
            onBearing?.invoke()
        }
        override fun onHorizontalAccuracyRadiusUpdated(vararg radius: Double, options: (ValueAnimator.() -> Unit)?) { accuracies.addAll(radius.toList()) }
        override fun onPuckLocationAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) = Unit
        override fun onPuckBearingAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) = Unit
        override fun onPuckAccuracyRadiusAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) = Unit
        override fun onError(error: LocationError) = Unit
    }
}
