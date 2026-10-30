package com.kungsbackacarcommunity.app.live

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Confine calls/collectors to one dispatcher (the UI dispatcher in production). */
@MainThread
class LiveMarkerAppearanceController(
    private val repository: LiveMarkerAppearanceRepository,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<Map<String, LiveMarkerAppearance>>(emptyMap())
    val appearances = state.asStateFlow()
    private val profiles = mutableMapOf<String, LiveMarkerProfile>()
    private val listeners = mutableMapOf<String, Job>()
    private val tokens = mutableMapOf<String, Any>()
    private var wanted: Set<String> = emptySet()
    private var viewerKey: String? = null
    private var epoch = 0L

    /** UID of the owning authenticated scope; null means logout. Include a session
     * revision only if that composition can survive logout/relogin with the same UID. */
    fun setRoster(viewerKey: String?, drawnUids: Set<String>) {
        val selected = if (viewerKey.isNullOrBlank()) emptySet() else drawnUids.asSequence()
            .filter { it.isNotBlank() && it.length <= 128 && '/' !in it }.sorted()
            .take(MAX_LIVE_MARKER_APPEARANCE_UIDS).toSet()
        if (this.viewerKey == viewerKey && selected == wanted) return
        if (this.viewerKey != viewerKey) {
            close()
            this.viewerKey = viewerKey
        }
        wanted = selected
        for (uid in listeners.keys.toList()) if (uid !in selected) {
            tokens.remove(uid)
            listeners.remove(uid)?.cancel()
            profiles.remove(uid)
        }
        publish()
        for (uid in selected) if (uid !in listeners) {
            val token = Any()
            tokens[uid] = token
            val generation = epoch
            listeners[uid] = scope.launch {
                try {
                    repository.observeProfile(uid).collect { profile ->
                        if (epoch == generation && tokens[uid] === token && uid in wanted) {
                            if (profile == null) profiles.remove(uid) else profiles[uid] = profile
                            publish()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (epoch == generation && tokens[uid] === token) {
                        profiles.remove(uid)
                        publish()
                    }
                }
            }
        }
    }

    private fun publish() {
        state.value = profiles.filterKeys { it in wanted }.mapValues { it.value.appearance() }
    }

    /** Also used on lifecycle stop. No account-scoped data survives a new collector. */
    fun close() {
        epoch++
        wanted = emptySet()
        viewerKey = null
        tokens.clear()
        listeners.values.forEach { it.cancel() }
        listeners.clear()
        profiles.clear()
        state.value = emptyMap()
    }
}
