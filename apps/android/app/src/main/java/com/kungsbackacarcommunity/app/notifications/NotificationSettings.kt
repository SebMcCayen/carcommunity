package com.kungsbackacarcommunity.app.notifications

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
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

/**
 * Notification settings (Phase 12 slice: notification preferences). Per-category
 * in-app / push opt-outs stored on the owner-writable
 * `userPrivate/{uid}.notificationPreferences` map (category → { inApp, push }),
 * a direct rules-validated write — no callable. Missing entries default to
 * enabled. The essential account-notice categories can never be disabled
 * (also enforced at delivery time by the backend writer). Pure Kotlin core for
 * testability; tracks functions/src/notifications/notifications-core.ts, the
 * Firestore-model port that this client's categories are defined against
 * (packages/shared/src/notifications.ts is the older pre-Firestore contract
 * and predates the social categories).
 */
object NotificationCategories {
    /**
     * Every category the backend defines in NOTIFICATION_CATEGORIES
     * (functions/src/notifications/notifications-core.ts), listed in the order
     * the settings screen renders them.
     *
     * The set of ids must stay in sync with that backend list — a category the
     * backend can deliver but this list omits is one the user can never turn
     * off, and an id here that the backend does not know is a toggle that
     * writes a preference nothing reads. The order is intentionally NOT in
     * sync: the [ESSENTIAL] account notices appear mid-list in the backend
     * order, but render locked-on here, so they are moved last rather than
     * splitting the tunable categories in two.
     */
    val ACTIVE: List<String> =
        listOf(
            "event_reminder",
            "event_updated",
            "event_cancelled",
            "admin_message",
            "subscription_status",
            "system_notice",
            "direct_message",
            "community_chat",
            "convoy_chat",
            "friend_request",
            "convoy_invite",
            "convoy_update",
            "account_warning",
            "account_suspension",
        )

    /**
     * Social categories — member-to-member activity (backend
     * SOCIAL_NOTIFICATION_CATEGORIES). Never essential: a user must always be
     * able to silence other members.
     */
    val SOCIAL: Set<String> =
        setOf(
            "direct_message",
            "community_chat",
            "convoy_chat",
            "friend_request",
            "convoy_invite",
            "convoy_update",
        )

    /** Essential account notices — cannot be disabled in-app or push. */
    val ESSENTIAL: Set<String> = setOf("account_warning", "account_suspension")

    fun isEssential(category: String): Boolean = category in ESSENTIAL
}

/** A category's per-channel opt-in. Both channels default to enabled. */
data class CategoryPreference(val inApp: Boolean = true, val push: Boolean = true)

/** Notification channel a toggle targets. */
enum class NotificationChannel { IN_APP, PUSH }

/**
 * The owner's per-category preferences. Missing categories read as enabled;
 * essential categories always read as fully enabled and reject toggles.
 */
data class NotificationPreferences(private val byCategory: Map<String, CategoryPreference>) {

    fun effective(category: String): CategoryPreference {
        if (NotificationCategories.isEssential(category)) return CategoryPreference(true, true)
        return byCategory[category] ?: CategoryPreference(true, true)
    }

    /** Returns a copy with one channel toggled; a no-op for essential categories. */
    fun withToggle(category: String, channel: NotificationChannel, enabled: Boolean): NotificationPreferences {
        if (NotificationCategories.isEssential(category)) return this
        val current = effective(category)
        val updated =
            when (channel) {
                NotificationChannel.IN_APP -> current.copy(inApp = enabled)
                NotificationChannel.PUSH -> current.copy(push = enabled)
            }
        return NotificationPreferences(byCategory + (category to updated))
    }

    /** Firestore representation: only non-essential categories are persisted. */
    fun toFirestoreMap(): Map<String, Map<String, Boolean>> =
        byCategory
            .filterKeys { !NotificationCategories.isEssential(it) }
            .mapValues { (_, pref) -> mapOf("inApp" to pref.inApp, "push" to pref.push) }

    companion object {
        val ALL_ENABLED = NotificationPreferences(emptyMap())

        @Suppress("UNCHECKED_CAST")
        fun fromFirestore(raw: Map<String, Any?>?): NotificationPreferences {
            if (raw == null) return ALL_ENABLED
            val parsed =
                raw.mapNotNull { (category, value) ->
                    val entry = value as? Map<String, Any?> ?: return@mapNotNull null
                    val inApp = entry["inApp"] as? Boolean ?: true
                    val push = entry["push"] as? Boolean ?: true
                    category to CategoryPreference(inApp = inApp, push = push)
                }.toMap()
            return NotificationPreferences(parsed)
        }
    }
}

/**
 * Runtime push-notification permission state (Android 13+ POST_NOTIFICATIONS).
 * Only GRANTED / DENIED are surfaced: without an in-app permission-request
 * flow the client can't reliably distinguish "never asked" from "denied", so
 * it doesn't claim an undetermined state it can't verify.
 */
enum class PushPermissionStatus { GRANTED, DENIED }

// ---------------------------------------------------------------------------
// Repository + coordinator
// ---------------------------------------------------------------------------

sealed interface NotificationSettingsState {
    data object Loading : NotificationSettingsState

    data class Loaded(val preferences: NotificationPreferences) : NotificationSettingsState
}

/** Owner-scoped notification-preferences access. Firebase-free for testability. */
interface NotificationSettingsRepository {
    fun observePreferences(uid: String): Flow<NotificationSettingsState>

    suspend fun savePreferences(uid: String, preferences: NotificationPreferences)
}

/** UI-facing status of a preferences save. */
sealed interface NotificationSettingsSaveStatus {
    data object Idle : NotificationSettingsSaveStatus

    data object Saving : NotificationSettingsSaveStatus

    data object Saved : NotificationSettingsSaveStatus

    data object Failed : NotificationSettingsSaveStatus
}

/** Orchestrates a toggle → save. Pure Kotlin, unit-testable with a fake repo. */
class NotificationSettingsCoordinator(
    private val repository: NotificationSettingsRepository,
) {
    private val state = MutableStateFlow<NotificationSettingsSaveStatus>(NotificationSettingsSaveStatus.Idle)
    val saveStatus: StateFlow<NotificationSettingsSaveStatus> = state.asStateFlow()

    suspend fun save(uid: String, preferences: NotificationPreferences) {
        if (state.value == NotificationSettingsSaveStatus.Saving) return
        state.value = NotificationSettingsSaveStatus.Saving
        try {
            repository.savePreferences(uid, preferences)
            state.value = NotificationSettingsSaveStatus.Saved
        } catch (cancellation: CancellationException) {
            state.value = NotificationSettingsSaveStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = NotificationSettingsSaveStatus.Failed
        }
    }

    fun reset() {
        state.value = NotificationSettingsSaveStatus.Idle
    }
}

/**
 * [NotificationSettingsRepository] backed by an owner Firestore listener/update
 * on userPrivate/{uid}. Guarded ([createIfAvailable]).
 */
class FirebaseNotificationSettingsRepository private constructor(
    private val firestore: FirebaseFirestore,
) : NotificationSettingsRepository {

    override fun observePreferences(uid: String): Flow<NotificationSettingsState> = callbackFlow {
        val registration =
            firestore.collection(USER_PRIVATE).document(uid).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Absent doc / transient error → render defaults (all enabled).
                    trySend(NotificationSettingsState.Loaded(NotificationPreferences.ALL_ENABLED))
                    return@addSnapshotListener
                }
                @Suppress("UNCHECKED_CAST")
                val raw = snapshot?.get(FIELD_PREFERENCES) as? Map<String, Any?>
                trySend(NotificationSettingsState.Loaded(NotificationPreferences.fromFirestore(raw)))
            }
        awaitClose { registration.remove() }
    }

    override suspend fun savePreferences(uid: String, preferences: NotificationPreferences) {
        val update =
            mapOf(
                FIELD_PREFERENCES to preferences.toFirestoreMap(),
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
                            task.exception
                                ?: IllegalStateException("notification preferences write failed without a cause"),
                        )
                    }
                }
        }
    }

    companion object {
        private const val USER_PRIVATE = "userPrivate"
        private const val FIELD_PREFERENCES = "notificationPreferences"

        fun createIfAvailable(context: Context): NotificationSettingsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseNotificationSettingsRepository(FirebaseFirestore.getInstance())
        }
    }
}
