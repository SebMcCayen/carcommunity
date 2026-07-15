package com.kungsbackacarcommunity.app.live

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.garage.VehicleValidation
import java.time.Instant
import java.time.Year
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [LiveLocationRepository] backed by the live.* callables (europe-west1) and a
 * Realtime Database value listener on the caller's own session node (Phase 12
 * slice 5).
 *
 * Callable names are the grouped-export forms live-startSession /
 * live-updatePosition / live-stopSession / live-hideMeNow
 * (contracts/functions/functions.json). Tasks are bridged to coroutines with
 * the same isActive-guarded pattern as the other repositories — no
 * play-services-await dependency. Construction is guarded ([createIfAvailable]
 * returns null when Firebase is not configured).
 */
class FirebaseLiveLocationRepository private constructor(
    private val functions: FirebaseFunctions,
    private val database: FirebaseDatabase,
) : LiveLocationRepository {

    override suspend fun startSession(duration: LiveSessionDuration) {
        call(START_SESSION, mapOf("duration" to duration.key))
    }

    override suspend fun updatePosition(coordinate: LiveCoordinate) {
        val coord =
            buildMap<String, Any> {
                put("latitude", coordinate.latitude)
                put("longitude", coordinate.longitude)
                put("recordedAt", coordinate.recordedAtIso)
                coordinate.accuracyMeters?.let { put("accuracyMeters", it) }
                coordinate.headingDegrees?.let { put("headingDegrees", it) }
                coordinate.speedMetersPerSecond?.let { put("speedMetersPerSecond", it) }
            }
        call(UPDATE_POSITION, mapOf("coordinate" to coord))
    }

    override suspend fun stopSession() {
        call(STOP_SESSION, mapOf("reason" to "user_stop"))
    }

    override suspend fun hideMeNow() {
        call(HIDE_ME_NOW, emptyMap())
    }

    override fun observeOwnSession(uid: String): Flow<LiveSessionInfo?> = callbackFlow {
        val ref = database.getReference("liveLocation/$uid/session")
        val listener =
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    trySend(snapshot.toSessionInfo())
                }

                override fun onCancelled(error: DatabaseError) {
                    // Read denied/interrupted: surface "no session" instead of
                    // hanging; a later successful read self-corrects.
                    trySend(null)
                }
            }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    override fun observeLatest(uid: String): Flow<LiveMarker?> = callbackFlow {
        // Per-uid marker read only (liveLocation/{uid}/latest); never scans the
        // collection. Mirrors observeOwnSession's callbackFlow/ValueEventListener.
        val ref = database.getReference("liveLocation/$uid/latest")
        val listener =
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    trySend(snapshot.toLiveMarker(uid))
                }

                override fun onCancelled(error: DatabaseError) {
                    // Read denied (not an active, non-suspended member) or
                    // interrupted: emit null (no marker) rather than hanging; a
                    // later successful read self-corrects.
                    trySend(null)
                }
            }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    private suspend fun call(name: String, data: Map<String, Any>): Unit =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(name)
                .call(data)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception
                                ?: IllegalStateException("$name failed without a cause"),
                        )
                    }
                }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val START_SESSION = "live-startSession"
        private const val UPDATE_POSITION = "live-updatePosition"
        private const val STOP_SESSION = "live-stopSession"
        private const val HIDE_ME_NOW = "live-hideMeNow"

        fun createIfAvailable(context: Context): LiveLocationRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseLiveLocationRepository(
                FirebaseFunctions.getInstance(REGION),
                FirebaseDatabase.getInstance(),
            )
        }
    }
}

/**
 * Maps the RTDB `latest` node to the Firebase-free [LiveMarker], or null when
 * absent (not sharing) or missing a coordinate. Reads only the marker-complete
 * fields written by live.updatePosition (latitude/longitude/displayName).
 */
private fun DataSnapshot.toLiveMarker(uid: String): LiveMarker? {
    if (!exists()) return null
    val latitude = child("latitude").getValue(Double::class.java) ?: return null
    val longitude = child("longitude").getValue(Double::class.java) ?: return null
    val displayName = child("displayName").getValue(String::class.java)
    return LiveMarker(
        uid = uid,
        latitude = latitude,
        longitude = longitude,
        displayName = displayName,
        mainCar = child("mainCar").toLiveMainCar(),
    )
}

/**
 * Maps the RTDB `mainCar` child (written by live.startSession's denormalization)
 * to [LiveMainCar], or null when absent or missing a required display field.
 * modelYear is read as a [Long] (RTDB stores integers as Long) and narrowed.
 */
private fun DataSnapshot.toLiveMainCar(): LiveMainCar? {
    if (!exists()) return null
    val make = child("make").getValue(String::class.java) ?: return null
    val model = child("model").getValue(String::class.java) ?: return null
    // Guard the Long before narrowing: a bare toInt() silently overflows on
    // malformed data (a client writing a huge/negative value would surface a
    // wrong year). Only accept plausible model years (same bounds the garage
    // form enforces); anything outside drops the whole main car rather than
    // render a bogus year.
    val modelYear =
        child("modelYear").getValue(Long::class.java)?.let { raw ->
            val minYear = VehicleValidation.MIN_MODEL_YEAR.toLong()
            val maxYear = VehicleValidation.maxModelYear(Year.now().value).toLong()
            if (raw in minYear..maxYear) raw.toInt() else null
        } ?: return null
    val imagePath = child("imagePath").getValue(String::class.java)
    return LiveMainCar(make = make, model = model, modelYear = modelYear, imagePath = imagePath)
}

/** Maps the RTDB session node to the Firebase-free [LiveSessionInfo]. */
private fun DataSnapshot.toSessionInfo(): LiveSessionInfo? {
    if (!exists()) return null
    val status = LiveSessionStatus.fromWire(child("status").getValue(String::class.java)) ?: return null
    val sessionId = child("id").getValue(String::class.java) ?: return null
    val duration = LiveSessionDuration.fromKey(child("duration").getValue(String::class.java))
    val expiresAtMillis =
        child("expiresAt").getValue(String::class.java)?.let { iso ->
            try {
                Instant.parse(iso).toEpochMilli()
            } catch (parse: Exception) {
                null
            }
        }
    return LiveSessionInfo(sessionId, status, duration, expiresAtMillis)
}
