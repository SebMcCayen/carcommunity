package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

// The navigation screen owns a different MapView. Only the home overlay activates the shell registry.
internal val LocalShellMapMarkerTaps = staticCompositionLocalOf<MapMarkerTaps?> { null }
internal val LocalMapMarkerTaps = staticCompositionLocalOf<MapMarkerTaps?> { null }

internal enum class MapMarkerLayer { Static, Drivers, Perks }

internal class MapMarkerTaps {
    class Target(val layer: MapMarkerLayer, val select: () -> Unit) {
        var bounds = Rect.Zero
    }

    private val targets = mutableSetOf<Target>()
    fun add(target: Target) { targets.add(target) }
    fun remove(target: Target) { targets.remove(target) }
    fun at(point: Offset): Target? {
        var best: Target? = null
        for (target in targets) {
            if (target.bounds.contains(point) && (best == null || target.layer >= best.layer)) best = target
        }
        return best
    }
}

/** Observes the map's stream. Only the up of a completed marker tap cancels the native tap. */
@Composable
internal fun Modifier.routeMapMarkerTaps(taps: MapMarkerTaps): Modifier {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return onGloballyPositioned { coordinates = it }.pointerInput(taps) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val layout = coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
            val target = taps.at(layout.localToRoot(down.position)) ?: return@awaitEachGesture
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.singleOrNull() ?: break
                if (change.id != down.id || change.isConsumed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                    change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) break
                if (!change.pressed && layout.isAttached && taps.at(layout.localToRoot(change.position)) === target) {
                    change.consume()
                    target.select()
                }
            } while (change.pressed)
        }
    }
}

/** Accessible marker action whose visual bounds do not intercept fingers above the MapView. */
@Composable
internal fun Modifier.mapMarkerTap(layer: MapMarkerLayer, enabled: Boolean = true, select: () -> Unit): Modifier {
    val taps = LocalMapMarkerTaps.current ?: return clickable(enabled = enabled, role = Role.Button, onClick = select)
    if (!enabled) return this
    val current = rememberUpdatedState(select)
    val target = remember(layer) { MapMarkerTaps.Target(layer) { current.value() } }
    DisposableEffect(taps, target) {
        taps.add(target)
        onDispose { taps.remove(target) }
    }
    return onGloballyPositioned { target.bounds = it.boundsInRoot() }
        .semantics {
            role = Role.Button
            onClick {
                current.value()
                true
            }
        }
        .onKeyEvent { event ->
            val activate = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter || event.key == Key.Spacebar
            if (activate && event.type == KeyEventType.KeyUp) current.value()
            activate
        }
        .focusable()
}
