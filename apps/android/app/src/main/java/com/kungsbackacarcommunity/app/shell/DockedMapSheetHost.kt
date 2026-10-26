package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** A reading sheet belongs to the activity's map viewport, never a second window. */
internal val LocalDockedMapSheetHost = staticCompositionLocalOf<DockedMapSheetState?> { null }

@Stable
internal class DockedMapSheetState {
    internal class Entry(val key: Any, val dismiss: () -> Unit, val content: @Composable () -> Unit)
    private data class Viewport(val priority: Int, val bounds: Rect)
    private val entries = mutableStateListOf<Entry>()
    private val viewports = mutableStateMapOf<Any, Viewport>()
    val active: Entry? get() = entries.lastOrNull()
    val viewport: Rect? get() = viewports.values.maxByOrNull { it.priority }?.bounds

    fun register(entry: Entry) { entries.removeAll { it.key === entry.key }; entries.add(entry) }
    fun remove(key: Any) { entries.removeAll { it.key === key } }
    fun setViewport(key: Any, priority: Int, bounds: Rect) {
        if (bounds.width > 0 && bounds.height > 0) viewports[key] = Viewport(priority, bounds)
    }
    fun removeViewport(key: Any) { viewports.remove(key) }
    fun dismissAll() {
        val previous = entries.toList().asReversed()
        entries.clear()
        previous.forEach { it.dismiss() }
    }
}

/** Mark the real body bounds. Its parent already reserves the actual bottom bar. */
internal fun Modifier.mapSheetViewport(priority: Int = 0): Modifier = composed {
    val host = LocalDockedMapSheetHost.current
    val owner = remember { Any() }
    DisposableEffect(host, owner) { onDispose { host?.removeViewport(owner) } }
    this.onGloballyPositioned { host?.setViewport(owner, priority, it.boundsInRoot()) }
        .then(if (host?.active != null) Modifier.clearAndSetSemantics {} else Modifier)
}

/**
 * Portal for map-owned sheets composed anywhere in the authenticated tree.
 * The footer remains outside this layer and therefore visible AND tappable.
 * When a full-screen route has no map viewport, use the safe activity bounds.
 */
@Composable
internal fun DockedMapSheetHost(ownerKey: Any? = null, content: @Composable BoxScope.() -> Unit) {
    val state = remember(ownerKey) { DockedMapSheetState() }
    var hostBounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDockedMapSheetHost provides state) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { hostBounds = it.boundsInRoot() }) {
            Box(Modifier.fillMaxSize()) { content() }
            val entry = state.active
            if (entry != null) {
                val bounds = state.viewport?.intersect(hostBounds)
                    ?.takeIf { it.width > 0f && it.height > 0f }
                val placement = if (bounds != null) {
                    Modifier.offset {
                        IntOffset((bounds.left - hostBounds.left).roundToInt(), (bounds.top - hostBounds.top).roundToInt())
                    }.size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                } else {
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                }
                Box(placement) { entry.content() }
            }
        }
    }
}
