package com.kungsbackacarcommunity.app.shell

/** Presentation lifetime only. Source positions, authorization and data never change here. */
internal class MapPresencePolicy {
    internal data class Frame(val id: String, val alpha: Float, val interactive: Boolean, val placement: Int)
    private data class Entry(val from: Float, val target: Float, val started: Long, val placement: Int)
    private val entries = linkedMapOf<String, Entry>()
    private var clock = 0L
    val currentTimeMs: Long get() = clock

    fun clear() { entries.clear() }
    fun trackedIds(): Set<String> = entries.keys.toSet()

    /** Allowed ids are CURRENT authorization, not the last rendered roster. */
    fun sync(visible: Set<String>, allowed: Set<String>, nowMs: Long,
        animations: Boolean = true, placements: Map<String, Int> = emptyMap()) {
        val now = advanceClock(nowMs)
        entries.keys.retainAll(allowed)
        val desired = visible.intersect(allowed)
        val all = entries.keys.toSet() + desired
        for (id in all) {
            val previous = entries[id]
            val target = if (id in desired) 1f else 0f
            val placement = placements[id] ?: previous?.placement ?: 0
            if (!animations) {
                if (target == 0f) entries.remove(id)
                else entries[id] = Entry(1f, 1f, now, placement)
            } else if (previous == null) {
                if (target > 0) entries[id] = Entry(0f, 1f, now, placement)
            } else if (target > 0 && previous.placement != placement) {
                // Placement changes at zero alpha; never slide a photo through the own puck.
                entries[id] = Entry(0f, 1f, now, placement)
            } else if (previous.target != target) {
                entries[id] = Entry(alpha(previous, now), target, now, placement)
            }
        }
        frames(now)
    }

    fun frames(nowMs: Long): List<Frame> {
        val now = advanceClock(nowMs)
        val result = mutableListOf<Frame>()
        for ((id, entry) in entries.toMap()) {
            val value = alpha(entry, now)
            if (entry.target == 0f && value <= 0f) entries.remove(id)
            else result += Frame(id, value, entry.target > 0f && value > 0f, entry.placement)
        }
        return result
    }

    fun isAnimating(nowMs: Long): Boolean {
        val now = advanceClock(nowMs)
        return entries.values.any { now - it.started < DURATION_MS && it.from != it.target }
    }

    private fun alpha(entry: Entry, now: Long): Float {
        val t = ((now - entry.started).toFloat() / DURATION_MS).coerceIn(0f, 1f)
        return entry.from + (entry.target - entry.from) * t
    }
    private fun advanceClock(now: Long): Long { clock = maxOf(clock, now); return clock }

    companion object { const val DURATION_MS = 200L }
}
