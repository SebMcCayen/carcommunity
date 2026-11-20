package com.kungsbackacarcommunity.app.privacy

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** Consent remains unknown until an authoritative server snapshot resolves. */
sealed interface PartnerStatsConsentState {
    data object Unknown : PartnerStatsConsentState

    /** Missing or legacy consent never grants optional analytics. Malformed fields remain unknown. */
    data object DefaultOff : PartnerStatsConsentState

    data class Chosen(val optIn: Boolean) : PartnerStatsConsentState
}

const val PARTNER_STATS_CONSENT_VERSION = 1

/** A stored legacy `true` was auto-provisioned and is not affirmative consent. */
fun partnerStatsConsentState(
    data: Map<String, Any?>?,
    fromCache: Boolean = false,
    hasPendingWrites: Boolean = false,
): PartnerStatsConsentState {
    if (data == null || fromCache || hasPendingWrites) return PartnerStatsConsentState.Unknown
    val choice = data["anonymousPartnerStatsOptIn"]
    if (data.containsKey("anonymousPartnerStatsOptIn") && choice !is Boolean) return PartnerStatsConsentState.Unknown
    val version = data["anonymousPartnerStatsConsentVersion"]
    val numericVersion = (version as? Number)?.toDouble()
    if (data.containsKey("anonymousPartnerStatsConsentVersion") &&
        (numericVersion == null || !numericVersion.isFinite() || numericVersion % 1.0 != 0.0)
    ) return PartnerStatsConsentState.Unknown
    val timestamp = data["anonymousPartnerStatsConsentAt"] as? Timestamp
    if (data.containsKey("anonymousPartnerStatsConsentAt") &&
        (timestamp == null || timestamp.seconds < 0 || (timestamp.seconds == 0L && timestamp.nanoseconds == 0))
    ) return PartnerStatsConsentState.Unknown
    if (choice == false) return PartnerStatsConsentState.Chosen(false)
    return if (choice == true && numericVersion == PARTNER_STATS_CONSENT_VERSION.toDouble() && timestamp != null) {
        PartnerStatsConsentState.Chosen(true)
    } else {
        PartnerStatsConsentState.DefaultOff
    }
}

/** Authoritative consent controls optional Partner Insights, including billboard interactions. */
fun PartnerStatsConsentState.allowsOptionalAnalytics(): Boolean =
    this == PartnerStatsConsentState.Chosen(true)

interface PartnerStatsRepository {
    fun observeConsent(uid: String): Flow<PartnerStatsConsentState>

    /** Explicit Save records both the choice and current consent evidence atomically. */
    suspend fun setOptIn(uid: String, optIn: Boolean)
}

/** UI-facing status of a save. */
sealed interface PartnerStatsSaveStatus {
    data object Idle : PartnerStatsSaveStatus

    data object Saving : PartnerStatsSaveStatus

    data object Saved : PartnerStatsSaveStatus

    data object Failed : PartnerStatsSaveStatus
}

/** Orchestrates the opt-in write (Phase 12 slice 19). Pure Kotlin. */
class PartnerStatsCoordinator(
    private val repository: PartnerStatsRepository,
) {
    private val state = MutableStateFlow<PartnerStatsSaveStatus>(PartnerStatsSaveStatus.Idle)
    val saveStatus: StateFlow<PartnerStatsSaveStatus> = state.asStateFlow()

    suspend fun save(uid: String, optIn: Boolean) {
        if (state.value == PartnerStatsSaveStatus.Saving) return
        state.value = PartnerStatsSaveStatus.Saving
        try {
            repository.setOptIn(uid, optIn)
            state.value = PartnerStatsSaveStatus.Saved
        } catch (cancellation: CancellationException) {
            state.value = PartnerStatsSaveStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = PartnerStatsSaveStatus.Failed
        }
    }

    fun reset() {
        state.value = PartnerStatsSaveStatus.Idle
    }
}

/**
 * [PartnerStatsRepository] backed by an owner Firestore listener/update on
 * userPrivate/{uid}. Guarded ([createIfAvailable]).
 */
class FirebasePartnerStatsRepository private constructor(
    private val firestore: FirebaseFirestore,
) : PartnerStatsRepository {

    override fun observeConsent(uid: String): Flow<PartnerStatsConsentState> = callbackFlow {
        val registration =
            firestore.collection(USER_PRIVATE).document(uid).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                val consent = if (error != null || snapshot == null) {
                    PartnerStatsConsentState.Unknown
                } else {
                    partnerStatsConsentState(
                        data = snapshot.data ?: if (snapshot.exists()) null else emptyMap(),
                        fromCache = snapshot.metadata.isFromCache,
                        hasPendingWrites = snapshot.metadata.hasPendingWrites(),
                    )
                }
                trySend(consent)
            }
        awaitClose { registration.remove() }
    }

    override suspend fun setOptIn(uid: String, optIn: Boolean) {
        val update =
            mapOf(
                "anonymousPartnerStatsOptIn" to optIn,
                "anonymousPartnerStatsConsentVersion" to PARTNER_STATS_CONSENT_VERSION,
                "anonymousPartnerStatsConsentAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        suspendCancellableCoroutine { continuation ->
            firestore
                .collection(USER_PRIVATE)
                .document(uid)
                .update(update)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("opt-in write failed without a cause"),
                        )
                    }
                }
        }
    }

    companion object {
        private const val USER_PRIVATE = "userPrivate"

        fun createIfAvailable(context: Context): PartnerStatsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebasePartnerStatsRepository(FirebaseFirestore.getInstance())
        }
    }
}
