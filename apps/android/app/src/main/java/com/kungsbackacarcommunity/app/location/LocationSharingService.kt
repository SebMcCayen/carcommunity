package com.kungsbackacarcommunity.app.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.live.FirebaseLiveLocationRepository
import com.kungsbackacarcommunity.app.live.LiveLocationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that streams the device GPS position to the backend via
 * live.updatePosition while a live-location session is active (Phase 12
 * slice 6).
 *
 * It requests periodic fused-location updates, maps each fix through
 * [BackgroundLocation.buildCoordinate], and publishes it through a
 * [LiveLocationRepository]. The repository is obtained via
 * [FirebaseLiveLocationRepository.createIfAvailable]; when Firebase is not
 * configured (e.g. CI builds with no google-services.json) it is null and the
 * service simply stops itself — nothing crashes at class-load or start.
 *
 * Real-device GPS and the runtime location/notification permission grants
 * CANNOT be exercised in this environment (no device); a missing location
 * permission surfaces as a [SecurityException] from requestLocationUpdates,
 * which is caught and stops the service.
 */
class LocationSharingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var repository: LiveLocationRepository? = null
    private var fusedClient: FusedLocationProviderClient? = null

    private val locationCallback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val repo = repository ?: return
                val fix = result.lastLocation ?: return
                val coordinate =
                    BackgroundLocation.buildCoordinate(
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        timeMillis = fix.time,
                        accuracyMeters = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null,
                        bearingDegrees = if (fix.hasBearing()) fix.bearing.toDouble() else null,
                        speedMps = if (fix.hasSpeed()) fix.speed.toDouble() else null,
                    )
                scope.launch {
                    try {
                        repo.updatePosition(coordinate)
                    } catch (c: CancellationException) {
                        // Preserve cooperative cancellation — never swallow.
                        throw c
                    } catch (_: Exception) {
                        // A single failed publish must not tear down sharing; the
                        // next fix retries. Details may reference the payload —
                        // never logged.
                    }
                }
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = FirebaseLiveLocationRepository.createIfAvailable(applicationContext)
        if (repo == null) {
            // Firebase not configured (e.g. CI / config-less build). Nothing to
            // publish to, so do not hold a foreground service — stop BEFORE
            // showing any foreground notification.
            stopSelf()
            return START_NOT_STICKY
        }
        repository = repo

        startForegroundNotification()

        val client = LocationServices.getFusedLocationProviderClient(applicationContext)
        fusedClient = client
        val request =
            LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                BackgroundLocation.UPDATE_INTERVAL_MS,
            )
                .setMinUpdateIntervalMillis(BackgroundLocation.MIN_UPDATE_INTERVAL_MS)
                .build()
        try {
            client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // Location permission not granted at runtime (grant is DEFERRED / no
            // device here). Cannot share — stop cleanly.
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        fusedClient?.removeLocationUpdates(locationCallback)
        fusedClient = null
        repository = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.liveLocation_backgroundNotificationTitle))
                .setContentText(getString(R.string.liveLocation_backgroundNotificationBody))
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.liveLocation_backgroundNotificationTitle),
                NotificationManager.IMPORTANCE_LOW,
            )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "live_location_sharing"
        private const val NOTIFICATION_ID = 4201
    }
}
