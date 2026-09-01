package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing state of the server-authoritative "your driving" statistics page. */
sealed interface DriveStatsUiState {
    data object Loading : DriveStatsUiState

    /** The aggregate failed to load. [code] is the callable status name, or null. */
    data class Error(val code: String? = null) : DriveStatsUiState

    /**
     * The aggregate loaded. [snapshot] is server-authoritative over the caller's
     * tier-visible drives; the screen renders its own "no drives yet" empty state
     * when [DriveStatsSnapshot.totalDrives] is 0.
     */
    data class Loaded(val snapshot: DriveStatsSnapshot) : DriveStatsUiState
}

/**
 * Loads the tier-visible statistics aggregate ([drives-stats]) as a [StateFlow],
 * replacing the old client-side fold over the History list (which silently became
 * "loaded page only" once history was paginated). Pure Kotlin (Firebase-free) so
 * it is unit-testable with a fake [DriveHistoryRepository].
 *
 * [load] is called when the Statistics page opens and again on any
 * subscription-tier change, so the figures always reflect the caller's current
 * tier. The month bounds are the viewer's LOCAL calendar month, resolved at the
 * composable edge ([DrivePeriodBoundaries]) and validated server-side.
 */
class DriveStatsCoordinator(
    private val repository: DriveHistoryRepository,
) {
    private val _state = MutableStateFlow<DriveStatsUiState>(DriveStatsUiState.Loading)
    val state: StateFlow<DriveStatsUiState> = _state.asStateFlow()

    suspend fun load(monthStartMillis: Long?, monthEndMillis: Long?) {
        _state.value = DriveStatsUiState.Loading
        try {
            val snapshot = repository.fetchStats(monthStartMillis, monthEndMillis)
            _state.value = DriveStatsUiState.Loaded(snapshot)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            _state.value = DriveStatsUiState.Error(code = failure.driveHistoryCode())
        }
    }
}
