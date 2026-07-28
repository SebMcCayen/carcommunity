package com.kungsbackacarcommunity.app.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of a mark-read action. */
sealed interface MarkReadStatus {
    data object Idle : MarkReadStatus

    data object Working : MarkReadStatus

    data object Failed : MarkReadStatus
}

/** Which delete failed, so the inbox can say the right sentence. */
enum class NotificationDeleteError {
    SINGLE,
    ALL,
}

/**
 * Orchestrates mark-read / mark-all-read and the optimistic deletes (Phase 12
 * slice 21). Pure Kotlin so it is unit-testable with a fake repository. The
 * inbox list is driven by the repository observer; this only tracks the
 * in-flight action and which rows are being optimistically hidden.
 *
 * OPTIMISTIC DELETE. A swipe hides the row at once — waiting for a round trip
 * would leave the finger's gesture visibly un-honoured — but the hiding is only
 * a filter over the snapshot ([Notifications.visibleItems]). If the callable
 * fails, the id comes straight back out of [pendingDeletes] and the row
 * returns, alongside an error the user can see. The one thing this must never
 * do is quietly drop a notification that still exists on the server, so the
 * failure path is the restore path, not a swallow.
 *
 * The deletes deliberately do NOT go through [execute]'s single-flight gate:
 * that gate exists to stop two mark-read calls overlapping, whereas swiping
 * two rows in quick succession is ordinary use and each delete addresses a
 * different document. Delete-all is the exception — it is re-entrancy-guarded
 * on its own flag, because a second sweep while the first is in flight would
 * only produce a redundant round trip.
 */
class NotificationsCoordinator(
    private val repository: NotificationsRepository,
) {
    private val state = MutableStateFlow<MarkReadStatus>(MarkReadStatus.Idle)
    val status: StateFlow<MarkReadStatus> = state.asStateFlow()

    private val pending = MutableStateFlow<Set<String>>(emptySet())

    /** Ids hidden from the inbox while their delete is in flight (or has just landed). */
    val pendingDeletes: StateFlow<Set<String>> = pending.asStateFlow()

    private val deleteFailure = MutableStateFlow<NotificationDeleteError?>(null)
    val deleteError: StateFlow<NotificationDeleteError?> = deleteFailure.asStateFlow()

    private val deletingAll = MutableStateFlow(false)

    suspend fun markRead(notificationId: String) = execute { repository.markRead(notificationId) }

    suspend fun markAllRead() = execute { repository.markAllRead() }

    /**
     * Hides [notificationId], deletes it, and puts it back if the server says
     * no. Already-pending ids are ignored so a repeated swipe cannot start a
     * second call whose failure would restore a row the first call removed.
     */
    suspend fun delete(notificationId: String) {
        if (notificationId in pending.value) return
        pending.value = pending.value + notificationId
        try {
            repository.deleteNotification(notificationId)
        } catch (cancellation: CancellationException) {
            pending.value = pending.value - notificationId
            throw cancellation
        } catch (failure: Exception) {
            pending.value = pending.value - notificationId
            deleteFailure.value = NotificationDeleteError.SINGLE
        }
    }

    /**
     * Hides every id in [visibleIds] and empties the inbox. On failure every id
     * this call hid is restored — and only those, so a single-row delete that
     * was already in flight keeps its own row hidden.
     */
    suspend fun deleteAll(visibleIds: Collection<String>) {
        if (deletingAll.value) return
        deletingAll.value = true
        val hidden = visibleIds.toSet() - pending.value
        pending.value = pending.value + hidden
        try {
            repository.deleteAll()
        } catch (cancellation: CancellationException) {
            pending.value = pending.value - hidden
            throw cancellation
        } catch (failure: Exception) {
            pending.value = pending.value - hidden
            deleteFailure.value = NotificationDeleteError.ALL
        } finally {
            deletingAll.value = false
        }
    }

    /**
     * Retires ids the server no longer returns — i.e. the deletes that landed.
     * Called for every snapshot; see [Notifications.prunePendingDeletes].
     */
    fun onSnapshot(items: List<AppNotification>) {
        val next = Notifications.prunePendingDeletes(pending.value, items)
        if (next != pending.value) pending.value = next
    }

    fun clearDeleteError() {
        deleteFailure.value = null
    }

    fun reset() {
        if (state.value == MarkReadStatus.Failed) state.value = MarkReadStatus.Idle
    }

    private suspend fun execute(action: suspend () -> Unit) {
        if (state.value == MarkReadStatus.Working) return
        state.value = MarkReadStatus.Working
        try {
            action()
            state.value = MarkReadStatus.Idle
        } catch (cancellation: CancellationException) {
            state.value = MarkReadStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = MarkReadStatus.Failed
        }
    }
}
