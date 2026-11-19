package com.kungsbackacarcommunity.app.shell

internal data class MapMarkerPresentation<T>(
    val markers: List<T>, val frames: Map<String, MapPresencePolicy.Frame>, val animating: Boolean,
)

/** Bounded public-marker snapshots shared by incident, event and sponsored layers. */
internal class MapMarkerPresence<T>(private val id: (T) -> String) {
    private val policy = MapPresencePolicy()
    private val snapshots = mutableMapOf<String, T>()
    fun clear() { policy.clear(); snapshots.clear() }
    fun frame(source: List<T>, nowMs: Long, active: Boolean, animations: Boolean,
              hidden: Set<String> = emptySet()): MapMarkerPresentation<T> {
        if (!active) clear() else {
            source.forEach { snapshots[id(it)] = it }
            policy.sync(source.map(id).toSet() - hidden, snapshots.keys.toSet(), nowMs, animations)
        }
        val frames = policy.frames(nowMs).associateBy { it.id }
        val markers = frames.keys.mapNotNull { snapshots[it] }
        snapshots.keys.retainAll(policy.trackedIds() + source.map(id))
        return MapMarkerPresentation(markers, frames, policy.isAnimating(nowMs))
    }
}

/** Source equality updates pixels in place; each native annotation keeps its id. */
internal class MapAnnotationReconciler<M, A>(private val id: (M) -> String) {
    private data class Entry<M, A>(val marker: M?, val annotation: A)
    private val entries = mutableMapOf<String, Entry<M, A>>()
    fun clear() = entries.clear()
    fun forEachAnnotation(block: (String, A) -> Unit) { entries.forEach { (id, value) -> block(id, value.annotation) } }
    fun reconcile(markers: List<M>, prepare: (M) -> Boolean, create: (M) -> A,
                  update: (A, M) -> Unit, delete: (A) -> Unit): Boolean {
        val desired = markers.associateBy(id)
        var complete = true
        for (key in entries.keys.toList()) if (key !in desired) {
            val entry = entries.getValue(key)
            if (runCatching { delete(entry.annotation) }.isSuccess) entries.remove(key) else complete = false
        }
        for ((key, marker) in desired) {
            val previous = entries[key]
            if (previous?.marker == marker) continue
            val applied = runCatching {
                if (!prepare(marker)) return@runCatching false
                val annotation = if (previous == null) create(marker) else {
                    entries[key] = Entry(null, previous.annotation)
                    update(previous.annotation, marker)
                    previous.annotation
                }
                entries[key] = Entry(marker, annotation)
                true
            }.getOrDefault(false)
            if (!applied) complete = false
        }
        return complete
    }
}
