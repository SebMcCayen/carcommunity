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
    private val registrationTokens = mutableMapOf<LocationConsumer, Long>()
    private var nextRegistrationToken = 0L
    private var emissionGeneration = 0L
    private var recording = false
    private var position: MapRecordingPosition? = null
    private var courseBearing = RecordingCourseBearing()
    private var recordingBearing: Double? = null
    private val motion = RecordingMapMotion()
    var renderedFrame: RecordingMapFrame? = null
        private set

    val canDisplayLocation: Boolean
        get() = !recording || fresh(position) != null

    fun update(recording: Boolean, position: MapRecordingPosition?, points: List<MapPoint> = emptyList()) {
        val changedSource = this.recording != recording
        this.recording = recording
        val next = if (recording) fresh(position) else null
        val changedPosition = this.position != next
        this.position = next
        if (changedSource || changedPosition) emissionGeneration++
        if (changedSource) {
            courseBearing = RecordingCourseBearing()
            recordingBearing = null
            motion.reset()
            renderedFrame = null
        }
        if (recording && next != null && (changedSource || changedPosition)) {
            recordingBearing = courseBearing.update(next)
            motion.update(next, points, recordingBearing, elapsedRealtime())
        }
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
            publishFrame(force = true)
        }
    }

    /** Drive one presentation frame; accepted recording data is never changed. */
    fun advance(): Boolean {
        publishFrame()
        return recording && fresh(position) != null && renderedFrame?.animating == true
    }

    private fun publishFrame(force: Boolean = false) {
        if (!recording) return
        val fix = fresh(position) ?: return
        val frame = motion.frame(elapsedRealtime()) ?: return
        val previous = renderedFrame
        // Make the matching trail available BEFORE native position callbacks.
        renderedFrame = frame
        if (force || previous == null || frame.point != previous.point || frame.bearingDegrees != previous.bearingDegrees) {
            val generation = ++emissionGeneration
            consumers.toList().forEach { emit(it, fix, frame, updateAccuracy = force, generation = generation) }
        }
    }

    override fun registerLocationConsumer(locationConsumer: LocationConsumer) {
        consumers.add(locationConsumer)
        registrationTokens[locationConsumer] = ++nextRegistrationToken
        if (recording) {
            fresh(position)?.let { fix ->
                motion.frame(elapsedRealtime())?.let { frame ->
                    renderedFrame = frame
                    emit(locationConsumer, fix, frame, updateAccuracy = true, generation = ++emissionGeneration)
                }
            }
        } else {
            deviceConsumers.remove(locationConsumer)?.let(deviceProvider::unRegisterLocationConsumer)
            registerDeviceConsumer(locationConsumer)
        }
    }

    override fun unRegisterLocationConsumer(locationConsumer: LocationConsumer) {
        consumers.remove(locationConsumer)
        registrationTokens.remove(locationConsumer)
        deviceConsumers.remove(locationConsumer)?.let(deviceProvider::unRegisterLocationConsumer)
    }

    private fun fresh(position: MapRecordingPosition?): MapRecordingPosition? = position?.takeIf {
        elapsedRealtime() - it.elapsedRealtimeMs in 0L..MAX_FIX_AGE_MS
    }

    private fun emit(
        consumer: LocationConsumer,
        fix: MapRecordingPosition,
        frame: RecordingMapFrame,
        updateAccuracy: Boolean,
        generation: Long,
    ) {
        val registration = registrationTokens[consumer] ?: return
        fun current() = recording && emissionGeneration == generation &&
            registrationTokens[consumer] == registration && fresh(position) != null
        if (!current()) return
        // The shared presentation clock already interpolated both values along
        // accepted route vertices. A second SDK animation would desynchronize the
        // camera, puck and visible breadcrumb tip.
        frame.bearingDegrees?.let { consumer.onBearingUpdated(it, options = { duration = 0L }) }
        if (!current()) return
        consumer.onLocationUpdated(Point.fromLngLat(frame.point.longitude, frame.point.latitude), options = { duration = 0L })
        // Accuracy changes at GPS cadence, never once per display frame.
        if (updateAccuracy && current()) consumer.onHorizontalAccuracyRadiusUpdated(fix.accuracyMeters)
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
