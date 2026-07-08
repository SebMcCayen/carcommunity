package com.kungsbackacarcommunity.app.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [PushTokenRepository] backed by the `notifications-registerPushToken` /
 * `notifications-unregisterPushToken` callables (europe-west1), matching the
 * backend contract in functions/src/notifications/pushTokens.ts:
 *
 * - register: { token, platform: "android", appVersion?, buildNumber? } —
 *   optional fields are OMITTED when absent (the zod schema rejects null).
 * - unregister: { tokenId } — the SHA-256 hex the backend derives; computed
 *   client-side by [PushTokens.tokenId]. The raw token is never logged.
 *
 * Guarded ([createIfAvailable]) like the rest of the Firebase wiring.
 */
class FirebasePushTokenRepository private constructor(
    private val functions: FirebaseFunctions,
    private val appVersion: String?,
    private val buildNumber: String?,
) : PushTokenRepository {

    override suspend fun register(token: String) {
        val payload = buildMap<String, Any?> {
            put("token", token)
            put("platform", PLATFORM_ANDROID)
            appVersion?.let { put("appVersion", it) }
            buildNumber?.let { put("buildNumber", it) }
        }
        call(REGISTER, payload)
    }

    override suspend fun unregister(token: String) {
        call(UNREGISTER, mapOf("tokenId" to PushTokens.tokenId(token)))
    }

    private suspend fun call(name: String, payload: Map<String, Any?>): Unit =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(name)
                .call(payload)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("$name failed without a cause"),
                        )
                    }
                }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val REGISTER = "notifications-registerPushToken"
        private const val UNREGISTER = "notifications-unregisterPushToken"
        private const val PLATFORM_ANDROID = "android"

        fun createIfAvailable(
            context: Context,
            appVersion: String? = null,
            buildNumber: String? = null,
        ): PushTokenRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebasePushTokenRepository(
                FirebaseFunctions.getInstance(REGION),
                appVersion = appVersion,
                buildNumber = buildNumber,
            )
        }
    }
}

/**
 * [PushTokenSource] backed by [FirebaseMessaging]. Returns null instead of
 * throwing when the token cannot be fetched (no Play Services, messaging not
 * ready) so callers degrade to a failed registration, not a crash.
 */
object FirebasePushTokenSource {

    fun createIfAvailable(context: Context): PushTokenSource? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return PushTokenSource { fetchToken() }
    }

    private suspend fun fetchToken(): String? =
        suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                continuation.resume(if (task.isSuccessful) task.result else null)
            }
        }
}
