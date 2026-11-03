package com.kungsbackacarcommunity.app.live

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive

/** A delayed first snapshot for one uid must not block other authorized values. */
internal fun observeLiveMarkerRoster(
    uids: List<String>,
    observe: (String) -> Flow<LiveMarker?>,
): Flow<List<LiveMarker?>> = if (uids.isEmpty()) {
    flowOf(emptyList())
} else {
    combine(uids.map { uid -> observe(uid).onStart { emit(null) } }) { it.toList() }
}

/**
 * Wait for the first usable camera instead of spending a poll interval on null.
 * Later pans only change the next sampled centre, never the callable cadence.
 * The delay starts after the consumer finishes its refresh, as before.
 */
internal fun <T : Any> nearbyLivePollSnapshots(
    camera: StateFlow<T?>,
    intervalMillis: Long,
): Flow<T> = flow {
    emit(camera.filterNotNull().first())
    while (currentCoroutineContext().isActive) {
        delay(intervalMillis)
        camera.value?.let { emit(it) }
    }
}
