package com.kungsbackacarcommunity.app.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Parent supplies ONLY UIDs reported as drawn by the authorized overlays. */
@Composable
fun rememberLiveMarkerAppearances(
    repository: LiveMarkerAppearanceRepository?,
    viewerKey: String?,
    drawnUids: Set<String>,
): Map<String, LiveMarkerAppearance> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val roster by rememberUpdatedState(drawnUids)
    // Fresh state immediately on account/repository replacement, before effects run.
    return key(repository, viewerKey, lifecycle) {
        val appearances by produceState<Map<String, LiveMarkerAppearance>>(emptyMap()) {
            if (repository == null || viewerKey.isNullOrBlank()) return@produceState
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val controller = LiveMarkerAppearanceController(repository, this)
                try {
                    launch { snapshotFlow { roster }.collect { controller.setRoster(viewerKey, it) } }
                    controller.appearances.collect { value = it }
                } finally {
                    controller.close()
                    value = emptyMap()
                }
            }
        }
        appearances
    }
}

/** Equal UID sets do not report again on GPS/camera changes. Disposal drops the roster. */
@Composable
internal fun ReportDrawnLiveMarkerUids(uids: Set<String>, onChanged: (Set<String>) -> Unit) {
    val callback by rememberUpdatedState(onChanged)
    LaunchedEffect(uids) { callback(uids) }
    DisposableEffect(Unit) { onDispose { callback(emptySet()) } }
}
