package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing state of the server-authoritative saved-drive history list. */
sealed interface DriveHistoryListState {
    data object Loading : DriveHistoryListState

    /**
     * The initial (first-page) load failed. [code] is the callable status name, or
     * null when the failure carried none — carried so the auto error report files a
     * stable dedup code rather than free text.
     */
    data class Error(val code: String? = null) : DriveHistoryListState

    /**
     * Drives are loaded (possibly across several appended pages).
     *
     * @property drives the tier-visible drives loaded so far, in server order.
     * @property tier the effective subscription tier the server reported.
     * @property hiddenDriveCount retained-but-hidden drives on this tier (0 for
     *   Supporter or when nothing is hidden) — drives the upgrade banner.
     * @property hasMore whether another page is available within the tier.
     * @property loadingMore a load-more request is in flight (append spinner).
     * @property loadMoreFailed the last load-more attempt failed (retry affordance);
     *   distinct from [Error] so a paging failure never blanks the already-shown list.
     */
    data class Loaded(
        val drives: List<SavedDrive>,
        val tier: DriveSubscriptionTier,
        val hiddenDriveCount: Int,
        val hasMore: Boolean,
        val loadingMore: Boolean = false,
        val loadMoreFailed: Boolean = false,
    ) : DriveHistoryListState
}

/**
 * Orchestrates the tier-gated history list ([drives-listHistory]) as a
 * [StateFlow], replacing the old raw `rides` snapshot listener. Pure Kotlin
 * (Firebase-free) so it is unit-testable with a fake [DriveHistoryRepository].
 *
 * Semantics the History screen relies on:
 * - [reload] resets to the first page (the initial load, "try again", and every
 *   subscription-tier change all route through it — a downgrade drops now-hidden
 *   drives, an upgrade reveals more);
 * - [loadMore] appends the next page for paid tiers (Community never pages: the
 *   callable rejects a cursor, and [Loaded.hasMore] is false, so the control is
 *   never offered);
 * - appended pages are de-duplicated by rideId, so a concurrent write shifting the
 *   cursor window can never render the same drive twice.
 */
class DriveHistoryCoordinator(
    private val repository: DriveHistoryRepository,
    private val pageSize: Int = DRIVE_HISTORY_PAGE_SIZE,
) {
    private val _state = MutableStateFlow<DriveHistoryListState>(DriveHistoryListState.Loading)
    val state: StateFlow<DriveHistoryListState> = _state.asStateFlow()

    /** Cursor for the next page, tracked outside the UI state (an internal detail). */
    private var nextCursorRideId: String? = null

    /** Loads (or reloads) the first page, discarding any previously loaded pages. */
    suspend fun reload() {
        _state.value = DriveHistoryListState.Loading
        nextCursorRideId = null
        try {
            val page = repository.listHistory(cursorRideId = null, pageSize = pageSize)
            nextCursorRideId = page.nextCursorRideId
            _state.value =
                DriveHistoryListState.Loaded(
                    drives = page.drives,
                    tier = page.tier,
                    hiddenDriveCount = page.hiddenDriveCount ?: 0,
                    hasMore = page.hasMore,
                )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            _state.value = DriveHistoryListState.Error(code = failure.driveHistoryCode())
        }
    }

    /**
     * Appends the next page, if one is available and none is already loading. A
     * failure leaves the loaded drives untouched and flips [Loaded.loadMoreFailed]
     * so the screen can offer a retry without blanking the list.
     */
    suspend fun loadMore() {
        val current = _state.value as? DriveHistoryListState.Loaded ?: return
        if (!current.hasMore || current.loadingMore) return
        val cursor = nextCursorRideId ?: return
        _state.value = current.copy(loadingMore = true, loadMoreFailed = false)
        try {
            val page = repository.listHistory(cursorRideId = cursor, pageSize = pageSize)
            nextCursorRideId = page.nextCursorRideId
            val merged = (current.drives + page.drives).distinctBy { it.rideId }
            _state.value =
                current.copy(
                    drives = merged,
                    hasMore = page.hasMore,
                    loadingMore = false,
                    loadMoreFailed = false,
                )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Re-read the current state: a reload may have raced ahead of this
            // append. Only annotate a still-Loaded state; never resurrect a stale one.
            (_state.value as? DriveHistoryListState.Loaded)?.let {
                _state.value = it.copy(loadingMore = false, loadMoreFailed = true)
            }
        }
    }
}

/** The callable status code carried by a [DriveHistoryException], else null. */
internal fun Exception.driveHistoryCode(): String? =
    (this as? DriveHistoryException)?.code
