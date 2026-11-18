package com.kungsbackacarcommunity.app.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory recent-use order, retained between chooser openings for one account. */
internal class StartDrivingVehicleOrder(private val load: suspend () -> List<String>) {
    private val mutableIds = MutableStateFlow<List<String>>(emptyList())
    val ids: StateFlow<List<String>> = mutableIds.asStateFlow()

    suspend fun refresh() {
        try {
            mutableIds.value = load().filter { it.isNotBlank() }.distinct()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Ranking is optional. Preserve the last successful order offline.
        }
    }
}
