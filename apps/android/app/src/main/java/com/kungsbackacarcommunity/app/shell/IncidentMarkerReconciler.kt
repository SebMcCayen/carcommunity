package com.kungsbackacarcommunity.app.shell

/** Retains native annotations by marker id; list order has no rendering meaning. */
internal class IncidentMarkerReconciler<A> {
    private data class Entry<A>(val marker: MapIncidentMarker?, val annotation: A)

    private val entries = mutableMapOf<String, Entry<A>>()

    fun clear() = entries.clear()

    fun forEachAnnotation(block: (String, A) -> Unit) {
        entries.forEach { (id, entry) -> block(id, entry.annotation) }
    }

    /**
     * Commit each successful operation independently. Failed image uploads or
     * native calls remain eligible for retry, even if a failed update already
     * mutated its native annotation object.
     */
    fun reconcile(
        markers: List<MapIncidentMarker>,
        prepare: (MapIncidentMarker) -> Boolean,
        create: (MapIncidentMarker) -> A,
        update: (A, MapIncidentMarker) -> Unit,
        delete: (A) -> Unit,
    ): Boolean {
        val desired = markers.associateBy { it.id }
        var complete = true
        for (id in entries.keys.toList()) {
            if (id in desired) continue
            val entry = entries.getValue(id)
            if (runCatching { delete(entry.annotation) }.isSuccess) {
                entries.remove(id)
            } else {
                complete = false
            }
        }
        for ((id, marker) in desired) {
            val entry = entries[id]
            if (entry?.marker == marker) continue
            val applied = runCatching {
                if (!prepare(marker)) return@runCatching false
                val annotation = if (entry == null) create(marker) else {
                    // A failed callback may already have mutated the native object.
                    entries[id] = Entry(null, entry.annotation)
                    update(entry.annotation, marker)
                    entry.annotation
                }
                entries[id] = Entry(marker, annotation)
                true
            }.getOrDefault(false)
            if (!applied) complete = false
        }
        return complete
    }
}
