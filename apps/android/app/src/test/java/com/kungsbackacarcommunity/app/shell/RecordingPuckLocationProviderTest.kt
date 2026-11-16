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
    )

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

    private class DeviceProvider : LocationProvider {
        val consumers = mutableSetOf<LocationConsumer>()
        override fun registerLocationConsumer(locationConsumer: LocationConsumer) { consumers.add(locationConsumer) }
        override fun unRegisterLocationConsumer(locationConsumer: LocationConsumer) { consumers.remove(locationConsumer) }
    }

    private class Consumer : LocationConsumer {
        val points = mutableListOf<Point>()
        val bearings = mutableListOf<Double>()
        val accuracies = mutableListOf<Double>()
        var immediatePosition = false
        override fun onLocationUpdated(vararg location: Point, options: (ValueAnimator.() -> Unit)?) {
            points.addAll(location)
            immediatePosition = options != null
        }
        override fun onBearingUpdated(vararg bearing: Double, options: (ValueAnimator.() -> Unit)?) { bearings.addAll(bearing.toList()) }
        override fun onHorizontalAccuracyRadiusUpdated(vararg radius: Double, options: (ValueAnimator.() -> Unit)?) { accuracies.addAll(radius.toList()) }
        override fun onPuckLocationAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) = Unit
        override fun onPuckBearingAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) = Unit
        override fun onPuckAccuracyRadiusAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) = Unit
        override fun onError(error: LocationError) = Unit
    }
}
