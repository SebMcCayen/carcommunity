package com.kungsbackacarcommunity.app.shell

import android.animation.ValueAnimator
import com.mapbox.common.location.LocationError
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.locationcomponent.LocationConsumer
import com.mapbox.maps.plugin.locationcomponent.LocationProvider

/**
 * Main-thread provider: ordinary device location outside recording, accepted
 * recorder fixes during recording. Switching sources fences queued device
 * callbacks, including callbacks from an earlier registration after a restart.
 */
internal class RecordingPuckLocationProvider(
    private val deviceProvider: LocationProvider,
    private val elapsedRealtime: () -> Long,
) : LocationProvider {
    private val consumers = mutableSetOf<LocationConsumer>()
    private val deviceConsumers = mutableMapOf<LocationConsumer, LocationConsumer>()
    private var recording = false
    private var position: MapRecordingPosition? = null

    val canDisplayLocation: Boolean
        get() = !recording || fresh(position) != null

    fun update(recording: Boolean, position: MapRecordingPosition?) {
        val changedSource = this.recording != recording
        this.recording = recording
        val next = if (recording) fresh(position) else null
        val changedPosition = this.position != next
        this.position = next
        if (changedSource) {
            if (recording) {
                val old = deviceConsumers.values.toList()
                deviceConsumers.clear() // Fence before unregistering (which may re-enter).
                old.forEach(deviceProvider::unRegisterLocationConsumer)
            } else {
                consumers.toList().forEach(::registerDeviceConsumer)
            }
        }
        if (recording && (changedSource || changedPosition)) {
            next?.let { fix -> consumers.toList().forEach { emit(it, fix) } }
        }
    }

    override fun registerLocationConsumer(locationConsumer: LocationConsumer) {
        consumers.add(locationConsumer)
        if (recording) {
            fresh(position)?.let { emit(locationConsumer, it) }
        } else {
            deviceConsumers.remove(locationConsumer)?.let(deviceProvider::unRegisterLocationConsumer)
            registerDeviceConsumer(locationConsumer)
        }
    }

    override fun unRegisterLocationConsumer(locationConsumer: LocationConsumer) {
        consumers.remove(locationConsumer)
        deviceConsumers.remove(locationConsumer)?.let(deviceProvider::unRegisterLocationConsumer)
    }

    private fun fresh(position: MapRecordingPosition?): MapRecordingPosition? = position?.takeIf {
        elapsedRealtime() - it.elapsedRealtimeMs in 0L..MAX_FIX_AGE_MS
    }

    private fun emit(consumer: LocationConsumer, fix: MapRecordingPosition) {
        // Use the exact 2D route vertex. Interpolating between sparse fixes cuts
        // across bends and leaves the dot behind the already-updated route head.
        consumer.onLocationUpdated(Point.fromLngLat(fix.point.longitude, fix.point.latitude), options = { duration = 0L })
        consumer.onHorizontalAccuracyRadiusUpdated(fix.accuracyMeters)
        fix.bearingDegrees?.takeIf { it.isFinite() && it in 0.0..360.0 }?.let {
            consumer.onBearingUpdated(it)
        }
    }

    private fun registerDeviceConsumer(consumer: LocationConsumer) {
        val proxy = object : LocationConsumer {
            fun current() = !recording && consumer in consumers && deviceConsumers[consumer] === this

            override fun onLocationUpdated(vararg location: Point, options: (ValueAnimator.() -> Unit)?) {
                if (current()) consumer.onLocationUpdated(*location, options = options)
            }

            override fun onBearingUpdated(vararg bearing: Double, options: (ValueAnimator.() -> Unit)?) {
                if (current()) consumer.onBearingUpdated(*bearing, options = options)
            }

            override fun onHorizontalAccuracyRadiusUpdated(vararg radius: Double, options: (ValueAnimator.() -> Unit)?) {
                if (current()) consumer.onHorizontalAccuracyRadiusUpdated(*radius, options = options)
            }

            override fun onPuckLocationAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) {
                if (current()) consumer.onPuckLocationAnimatorDefaultOptionsUpdated(options)
            }

            override fun onPuckBearingAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) {
                if (current()) consumer.onPuckBearingAnimatorDefaultOptionsUpdated(options)
            }

            override fun onPuckAccuracyRadiusAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) {
                if (current()) consumer.onPuckAccuracyRadiusAnimatorDefaultOptionsUpdated(options)
            }

            override fun onError(error: LocationError) {
                if (current()) consumer.onError(error)
            }
        }
        deviceConsumers[consumer] = proxy
        deviceProvider.registerLocationConsumer(proxy)
    }

    companion object {
        const val MAX_FIX_AGE_MS = 15_000L
    }
}
