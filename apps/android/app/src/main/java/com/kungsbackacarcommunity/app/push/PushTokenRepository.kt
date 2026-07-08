package com.kungsbackacarcommunity.app.push

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * FCM token registration domain (Phase 12 slice 21, push portion). Pure
 * Kotlin — JVM-testable with fakes; the Firebase-backed implementation lives
 * in [FirebasePushTokenRepository].
 */

/** Backend push-token registration (notifications-registerPushToken / -unregisterPushToken). */
interface PushTokenRepository {
    /** Registers the RAW token; the backend stores only its SHA-256 hash. Idempotent. */
    suspend fun register(token: String)

    /**
     * Unregisters the RAW token; implementations derive the backend tokenId
     * from it ([PushTokens.tokenId]). The raw token never leaves the client.
     * Idempotent.
     */
    suspend fun unregister(token: String)
}

/** Provides the device's current FCM registration token (null when unavailable). */
fun interface PushTokenSource {
    suspend fun currentToken(): String?
}

object PushTokens {
    /**
     * The tokenId the backend derives and expects for unregistration: the
     * SHA-256 hex of the raw token (notifications-core.ts `hashPushToken`).
     * The raw token is never stored or logged.
     */
    fun tokenId(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Registration state machine: idle → registering → registered | failed. */
sealed interface PushRegistrationStatus {
    data object Idle : PushRegistrationStatus

    data object Registering : PushRegistrationStatus

    data object Registered : PushRegistrationStatus

    data object Failed : PushRegistrationStatus
}

/**
 * Fetches the current FCM token and registers/unregisters it against the
 * backend. One action runs at a time; cancellation is rethrown (never
 * swallowed); any other failure lands in [PushRegistrationStatus.Failed] —
 * push registration is best-effort and must never break sign-in.
 */
class PushRegistrationCoordinator(
    private val repository: PushTokenRepository,
    private val tokenSource: PushTokenSource,
) {
    private val state = MutableStateFlow<PushRegistrationStatus>(PushRegistrationStatus.Idle)
    val status: StateFlow<PushRegistrationStatus> = state.asStateFlow()

    // Atomic check-and-set guard shared by BOTH actions: only the coroutine
    // that wins tryLock() runs, so concurrent calls can't double-register (the
    // check-then-set on `state` alone raced), and a register and an unregister
    // can never interleave. Held for the whole critical section, always
    // released in finally. Mirrors ImageUploadCoordinator (PR #283).
    private val actionLock = Mutex()

    /** Registers the device's current token (call once a user is signed in). */
    suspend fun registerCurrentToken() {
        // Atomic guard: if another action already holds the lock, do nothing.
        if (!actionLock.tryLock()) return
        try {
            state.value = PushRegistrationStatus.Registering
            val token = tokenSource.currentToken()
            if (token.isNullOrBlank()) {
                // No token available (messaging not ready) — nothing to retry now.
                state.value = PushRegistrationStatus.Failed
                return
            }
            repository.register(token)
            state.value = PushRegistrationStatus.Registered
        } catch (cancellation: CancellationException) {
            state.value = PushRegistrationStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = PushRegistrationStatus.Failed
        } finally {
            actionLock.unlock()
        }
    }

    /** Unregisters the device's current token (call before signing out). */
    suspend fun unregisterCurrentToken() {
        // Atomic guard: if another action already holds the lock, do nothing.
        if (!actionLock.tryLock()) return
        try {
            state.value = PushRegistrationStatus.Registering
            val token = tokenSource.currentToken()
            if (!token.isNullOrBlank()) {
                repository.unregister(token)
            }
            state.value = PushRegistrationStatus.Idle
        } catch (cancellation: CancellationException) {
            state.value = PushRegistrationStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = PushRegistrationStatus.Failed
        } finally {
            actionLock.unlock()
        }
    }
}
