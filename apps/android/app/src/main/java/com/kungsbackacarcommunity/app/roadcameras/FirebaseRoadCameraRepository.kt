package com.kungsbackacarcommunity.app.roadcameras

import android.content.Context
import android.util.Base64
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class FirebaseRoadCameraRepository(private val functions: FirebaseFunctions) : RoadCameraRepository {
    override suspend fun catalog(): RoadCameraCatalog {
        val response = call("roadCameras-catalog", emptyMap())
        val rows = response["cameras"] as? List<*> ?: error("Missing camera catalog")
        require(rows.size <= 10_000)
        return RoadCameraCatalog(rows.mapNotNull(RoadCameraParser::parse), response["updatedAt"] as? String)
    }
    override suspend fun photo(cameraId: String): RoadCameraPhoto {
        val data = call("roadCameras-photo", mapOf("cameraId" to cameraId))
        val encoded = data["base64"] as? String ?: error("Missing camera image")
        require(encoded.length <= 180_000)
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.isNotEmpty() && bytes.size <= CachedRoadCameraRepository.MAX_PHOTO_BYTES)
        val contentType = data["contentType"] as? String ?: error("Missing image type")
        require(contentType in setOf("image/jpeg", "image/png"))
        return RoadCameraPhoto(bytes, contentType, data["photoTime"] as? String)
    }
    private suspend fun call(name: String, payload: Map<String, Any>): Map<*, *> =
        suspendCancellableCoroutine { continuation ->
            functions.getHttpsCallable(name).call(payload).addOnCompleteListener { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                if (task.isSuccessful) {
                    val result = task.result?.data as? Map<*, *>
                    if (result != null) continuation.resume(result)
                    else continuation.resumeWithException(IllegalStateException("Invalid camera response"))
                } else {
                    val error = task.exception
                    continuation.resumeWithException(
                        if ((error as? FirebaseFunctionsException)?.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED)
                            RoadCameraRateLimited() else error ?: IllegalStateException("Camera request failed"))
                }
            }
        }
    companion object {
        fun createIfAvailable(context: Context): RoadCameraRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return CachedRoadCameraRepository(File(context.cacheDir, "road-cameras-v1"),
                FirebaseRoadCameraRepository(FirebaseFunctions.getInstance("europe-west1")))
        }
    }
}

object RoadCameraParser {
    fun parse(raw: Any?): RoadCamera? {
        val row = raw as? Map<*, *> ?: return null
        val id = (row["id"] as? String)?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return null
        val type = RoadCameraType.fromWire(row["type"] as? String) ?: return null
        val name = (row["name"] as? String)?.takeIf { it.length <= 300 } ?: return null
        val latitude = (row["latitude"] as? Number)?.toDouble() ?: return null
        val longitude = (row["longitude"] as? Number)?.toDouble() ?: return null
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return RoadCamera(id, type, name, latitude, longitude,
            (row["direction"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..360.0 })
    }
}
