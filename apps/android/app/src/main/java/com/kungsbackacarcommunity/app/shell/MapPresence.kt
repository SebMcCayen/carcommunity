package com.kungsbackacarcommunity.app.shell

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal val LocalMapPresentationActive = staticCompositionLocalOf { true }

internal data class MapPresentItem<T>(val id: String, val value: T, val alpha: Float, val interactive: Boolean)

/** One bounded ticker per changing layer; current geography is never buffered here. */
@Composable
internal fun <T> rememberMapPresence(
    items: Map<String, T>, allowed: Set<String> = items.keys, active: Boolean = true,
    placements: Map<String, Int> = emptyMap(), retainRemovedPublicItems: Boolean = false,
): List<MapPresentItem<T>> {
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val enabled = active && resumed && LocalMapPresentationActive.current
    val policy = remember { MapPresencePolicy() }
    val payloads = remember { mutableMapOf<String, T>() }
    var frames by remember { mutableStateOf(emptyList<MapPresencePolicy.Frame>()) }
    // Positions and image metadata remain live without restarting the opacity clock.
    SideEffect { if (enabled) payloads.putAll(items) }
    val ids = items.keys.toSet()
    LaunchedEffect(ids, allowed, placements, enabled) {
        if (!enabled) {
            policy.clear(); payloads.clear(); frames = emptyList()
            return@LaunchedEffect
        }
        payloads.putAll(items)
        val permitted = if (retainRemovedPublicItems) allowed + payloads.keys else allowed
        if (!retainRemovedPublicItems) payloads.keys.retainAll(permitted)
        policy.sync(ids, permitted, SystemClock.uptimeMillis(), ValueAnimator.areAnimatorsEnabled(), placements)
        val startedMs = policy.currentTimeMs
        var displayMs = startedMs
        var firstFrame: Long? = null
        do {
            frames = policy.frames(displayMs)
            payloads.keys.retainAll(policy.trackedIds() + ids)
            if (!policy.isAnimating(displayMs)) break
            val frameNanos = withFrameNanos { it }
            val origin = firstFrame ?: frameNanos.also { firstFrame = it }
            displayMs = startedMs + (frameNanos - origin) / 1_000_000L
            if (!ValueAnimator.areAnimatorsEnabled()) policy.sync(ids, permitted, displayMs, false, placements)
        } while (true)
        frames = policy.frames(displayMs)
    }
    if (!enabled) return emptyList()
    // Authorization and placement filtering happen NOW, before an effect can run.
    return frames.mapNotNull { frame ->
        if (!retainRemovedPublicItems && frame.id !in allowed) return@mapNotNull null
        val payload = items[frame.id] ?: payloads[frame.id] ?: return@mapNotNull null
        val samePlacement = placements[frame.id]?.let { it == frame.placement } ?: true
        MapPresentItem(frame.id, payload, if (samePlacement) frame.alpha else 0f,
            samePlacement && frame.interactive && frame.id in items)
    }
}
