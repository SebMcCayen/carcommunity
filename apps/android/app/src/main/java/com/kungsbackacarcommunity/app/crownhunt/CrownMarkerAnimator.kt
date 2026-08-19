package com.kungsbackacarcommunity.app.crownhunt

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The phase/timing brain behind the crown map markers' spawn and despawn
 * animations — a PURE, clock-free state holder so every transform value the map
 * draws is unit-tested here rather than eyeballed on one device.
 *
 * ## What it animates, and why it is pure
 *
 * The crowns are drawn as Mapbox style images on the GL surface
 * ([com.kungsbackacarcommunity.app.shell.MapboxMapSurface]); the surface cannot
 * be JVM-tested, so the ARITHMETIC of the animation — when a crown starts, how
 * its scale/rotation/opacity move over its lifetime, when it is finished and may
 * be dropped — lives here, where a test drives it against an injected `nowMs`.
 * The surface is left with only the mechanical job of pushing the numbers this
 * holder produces onto each annotation (`iconSize`, `iconRotate`, `iconOpacity`)
 * every frame.
 *
 * ## The two sequences (car/game-inspired)
 *
 *  - SPAWN: a small light shines at the spot, then the crown pops up (scale-in
 *    with a slight overshoot, the way a pickup lands in a driving game), spins a
 *    little as it settles, and comes to rest. See [spawnState].
 *  - DESPAWN: a brief spark, then the crown shrinks and spins out while it fades.
 *    See [despawnState].
 *
 * ## Appear / disappear detection, and STAGGERING
 *
 * [sync] is fed the FULL set of crown ids that should currently be on the map
 * (the diff source). An id that is new → starts a spawn; an id that has vanished
 * → starts a despawn (and is kept rendered until that despawn finishes, so a
 * removed crown animates OUT rather than blinking away). A "collected-by-you"
 * crown (#929) never leaves the id set — it stays live for others — so its state
 * change does NOT reach this holder as a despawn; only a genuine expire/removal
 * does.
 *
 * When several crowns appear in the SAME [sync] (the common case right after a
 * replenish pass fills an area's cells), their spawn starts are STAGGERED by
 * [staggerStepMs] in a stable id order, so a batch reveals a few hundred ms
 * apart instead of all popping at the same instant. This is the client half of
 * the "don't all appear at once" fix; the backend half caps how many crowns one
 * pass creates per cell (`MAX_NEW_CROWNS_PER_CELL_PER_PASS`).
 */
class CrownMarkerAnimator(
    private val spawnDurationMs: Long = DEFAULT_SPAWN_DURATION_MS,
    private val despawnDurationMs: Long = DEFAULT_DESPAWN_DURATION_MS,
    private val staggerStepMs: Long = DEFAULT_STAGGER_STEP_MS,
    private val maxStaggerSteps: Int = DEFAULT_MAX_STAGGER_STEPS,
) {
    private enum class Kind { SPAWNING, SETTLED, DESPAWNING }

    private data class Record(
        val kind: Kind,
        /**
         * For a spawn, the instant the pop actually BEGINS (arrival + its stagger
         * offset), so a batch member can be scheduled slightly in the future. For
         * a despawn, the instant it began. For a settled crown, unused.
         */
        val startAtMs: Long,
    )

    private val records = LinkedHashMap<String, Record>()

    /**
     * Reconciles the tracked set against [currentIds] — the ids that should be on
     * the map right now — at [nowMs].
     *
     * A genuinely NEW id starts a (staggered) spawn; an id that dropped out of
     * [currentIds] starts a despawn.
     *
     * An id that REAPPEARS while it was despawning is NOT re-spawned on a future
     * stagger offset — that would blink it out (a pending spawn is not drawn, and
     * the surface deletes an annotation it did not draw this frame), which is
     * exactly the flicker seen when Firestore momentarily drops an id or a crown
     * is removed and re-added across two quick syncs. Instead its despawn is
     * CANCELLED and it snaps back to a steady, present state immediately, so it
     * never leaves the map.
     *
     * Idempotent for an unchanged set: a crown already spawning or settled is left
     * exactly as it is, so re-syncing the same ids never re-triggers an animation.
     */
    fun sync(currentIds: Set<String>, nowMs: Long) {
        // Genuinely-new ids (never seen). Sorted for a STABLE stagger order so the
        // reveal sequence is deterministic and testable rather than dependent on
        // set iteration. A reappearing (despawning) id is handled separately below
        // — it must NOT be given a delayed spawn.
        val newlyAppearing = ArrayList<String>()
        for (id in currentIds) {
            val existing = records[id]
            when {
                existing == null -> newlyAppearing += id
                // Reappeared mid-despawn: cancel the despawn and treat it as
                // present RIGHT NOW (steady state), so it never blinks out.
                existing.kind == Kind.DESPAWNING ->
                    records[id] = Record(Kind.SETTLED, startAtMs = nowMs)
                // Already spawning or settled — leave its timeline untouched.
                else -> Unit
            }
        }

        newlyAppearing.sort()
        newlyAppearing.forEachIndexed { index, id ->
            // Cap the stagger so a large batch cannot push the last crown minutes
            // into the future — beyond the cap they share the maximum offset.
            val steps = min(index, maxStaggerSteps)
            records[id] = Record(Kind.SPAWNING, startAtMs = nowMs + steps * staggerStepMs)
        }

        // Ids that left the set → begin a despawn (once). A crown already
        // despawning is left on its existing timeline.
        for ((id, record) in records) {
            if (id !in currentIds && record.kind != Kind.DESPAWNING) {
                records[id] = Record(Kind.DESPAWNING, startAtMs = nowMs)
            }
        }
    }

    /**
     * Advances internal state to [nowMs] and returns the animation state of every
     * crown that should currently be DRAWN — spawning crowns whose start has
     * arrived, settled crowns, and despawning crowns whose animation has not yet
     * finished. Spawning crowns still waiting out their stagger offset, and
     * despawns that have completed, are omitted (the latter are also forgotten).
     *
     * Mutating on a query is deliberate: this is a stateful holder driven once
     * per frame, and pruning finished despawns here keeps the map's create/delete
     * bookkeeping and this holder from drifting apart.
     */
    fun frame(nowMs: Long): List<CrownAnimationState> {
        val out = ArrayList<CrownAnimationState>(records.size)
        val finished = ArrayList<String>()
        for ((id, record) in records) {
            when (record.kind) {
                Kind.SPAWNING -> {
                    val elapsed = nowMs - record.startAtMs
                    when {
                        // Still waiting out its stagger offset — not drawn yet, so
                        // it POPS in at its start rather than sitting invisible.
                        elapsed < 0 -> Unit
                        elapsed >= spawnDurationMs -> {
                            records[id] = record.copy(kind = Kind.SETTLED)
                            out += settledState(id)
                        }
                        else -> out += spawnState(id, elapsed.toFloat() / spawnDurationMs)
                    }
                }
                Kind.SETTLED -> out += settledState(id)
                Kind.DESPAWNING -> {
                    val elapsed = nowMs - record.startAtMs
                    if (elapsed >= despawnDurationMs) {
                        finished += id
                    } else {
                        val t = (elapsed.coerceAtLeast(0)).toFloat() / despawnDurationMs
                        out += despawnState(id, t)
                    }
                }
            }
        }
        for (id in finished) records.remove(id)
        return out
    }

    /**
     * Whether any crown is mid-animation (a pending or in-flight spawn, or an
     * unfinished despawn) at [nowMs] — the signal the surface uses to keep or stop
     * its per-frame ticker. Does NOT mutate, so it is safe to poll.
     */
    fun isAnimating(nowMs: Long): Boolean =
        records.any { (_, record) ->
            when (record.kind) {
                Kind.SPAWNING -> nowMs - record.startAtMs < spawnDurationMs
                Kind.DESPAWNING -> nowMs - record.startAtMs < despawnDurationMs
                Kind.SETTLED -> false
            }
        }

    /**
     * Every id the animator still tracks — spawning (pending or in-flight),
     * settled, or despawning-but-not-finished. The surface uses it to prune its
     * retained per-id appearance cache to exactly what may still be drawn, so a
     * finished-despawn crown's bitmap params are not held forever. Reflects the
     * state as of the last [frame] call (which is what prunes finished despawns).
     */
    fun trackedIds(): Set<String> = records.keys.toSet()

    /** Drops all tracked state — the layer went away (map left, flag off). */
    fun clear() {
        records.clear()
    }

    private fun settledState(id: String): CrownAnimationState =
        CrownAnimationState(
            id = id,
            phase = CrownAnimationPhase.SETTLED,
            scale = 1f,
            rotationDegrees = 0f,
            shineAlpha = 0f,
            contentAlpha = 1f,
        )

    /**
     * SPAWN transforms at normalized progress [t] in [0, 1]: the light shines, the
     * crown pops up with a slight overshoot, spins a little, and settles.
     */
    private fun spawnState(id: String, t: Float): CrownAnimationState {
        val clamped = t.coerceIn(0f, 1f)
        // Shine: a quick light flash at the spot, peaking early and gone by the
        // time the crown is fully up.
        val shine =
            if (clamped < SHINE_FRACTION) sin(PI * (clamped / SHINE_FRACTION)).toFloat() else 0f
        // Pop-in: the crown starts appearing just after the light, scaling from
        // nothing up past 1 and settling back — easeOutBack's overshoot.
        val popT = ((clamped - POP_START_FRACTION) / (1f - POP_START_FRACTION)).coerceIn(0f, 1f)
        val scale = if (clamped < POP_START_FRACTION) 0f else easeOutBack(popT)
        // Spin: starts tilted and unwinds to upright as it settles.
        val rotation = -SPAWN_SPIN_DEGREES * (1f - easeOutCubic(clamped))
        // Fade the glyph in fast, a beat after the light appears.
        val alpha = ((clamped - ALPHA_START_FRACTION) / ALPHA_RAMP_FRACTION).coerceIn(0f, 1f)
        return CrownAnimationState(
            id = id,
            phase = CrownAnimationPhase.SPAWNING,
            scale = scale,
            rotationDegrees = rotation,
            shineAlpha = shine,
            contentAlpha = alpha,
        )
    }

    /**
     * DESPAWN transforms at normalized progress [t] in [0, 1]: a spark, then the
     * crown shrinks and spins out while it fades.
     */
    private fun despawnState(id: String, t: Float): CrownAnimationState {
        val clamped = t.coerceIn(0f, 1f)
        val spark =
            if (clamped < SPARK_FRACTION) {
                (sin(PI * (clamped / SPARK_FRACTION)).toFloat()) * SPARK_STRENGTH
            } else {
                0f
            }
        val scale = 1f - easeInCubic(clamped)
        val rotation = DESPAWN_SPIN_DEGREES * easeInCubic(clamped)
        // Linear fade so the crown lingers slightly as it shrinks, rather than
        // vanishing before it has visibly gone.
        val alpha = 1f - clamped
        return CrownAnimationState(
            id = id,
            phase = CrownAnimationPhase.DESPAWNING,
            scale = scale,
            rotationDegrees = rotation,
            shineAlpha = spark,
            contentAlpha = alpha,
        )
    }

    companion object {
        /** Spawn animation length — long enough to read the pop + spin, short enough not to nag. */
        const val DEFAULT_SPAWN_DURATION_MS: Long = 620

        /** Despawn animation length — a touch quicker than the spawn; it is an exit, not an entrance. */
        const val DEFAULT_DESPAWN_DURATION_MS: Long = 420

        /** Delay between successive crowns' spawn starts when a batch appears together. */
        const val DEFAULT_STAGGER_STEP_MS: Long = 130

        /**
         * Cap on stagger steps: beyond this many crowns in one batch, the extras
         * share the maximum offset rather than trailing ever further behind. Keeps
         * a big fill (a whole area's cells) revealing over ~[DEFAULT_STAGGER_STEP_MS]
         * × this, not minutes.
         */
        const val DEFAULT_MAX_STAGGER_STEPS: Int = 6

        /** Fraction of the spawn during which the light shines. */
        private const val SHINE_FRACTION = 0.35f

        /** The crown starts scaling up only after the light has begun. */
        private const val POP_START_FRACTION = 0.10f

        /** The glyph starts fading in at this fraction… */
        private const val ALPHA_START_FRACTION = 0.05f

        /** …over this much of the spawn. */
        private const val ALPHA_RAMP_FRACTION = 0.15f

        /** How far the crown is tilted at the start of its spin, in degrees. */
        private const val SPAWN_SPIN_DEGREES = 160f

        /** How far a despawning crown spins out, in degrees. */
        private const val DESPAWN_SPIN_DEGREES = 200f

        /** Fraction of the despawn during which the exit spark flashes. */
        private const val SPARK_FRACTION = 0.4f

        /** Peak strength of the exit spark. */
        private const val SPARK_STRENGTH = 0.85f

        /** Overshoot constant for [easeOutBack] — the standard 1.70158 tension. */
        private const val BACK_C1 = 1.70158f
        private const val BACK_C3 = BACK_C1 + 1f

        /** easeOutBack — overshoots past 1 then settles, giving the pop a little kick. */
        fun easeOutBack(x: Float): Float {
            val p = x - 1f
            return 1f + BACK_C3 * p * p * p + BACK_C1 * p * p
        }

        /** easeOutCubic — fast then gentle, for a spin that unwinds and settles. */
        fun easeOutCubic(x: Float): Float {
            val p = 1f - x
            return 1f - p * p * p
        }

        /** easeInCubic — gentle then fast, for a shrink/spin that accelerates away. */
        fun easeInCubic(x: Float): Float = x * x * x
    }
}

/** The phase a crown marker is in — see [CrownMarkerAnimator]. */
enum class CrownAnimationPhase { SPAWNING, SETTLED, DESPAWNING }

/**
 * The draw-time transform for one crown marker at a given frame, produced by
 * [CrownMarkerAnimator.frame]. All fields are ready to push straight onto the
 * annotation: [scale] onto `iconSize`, [rotationDegrees] onto `iconRotate`,
 * [contentAlpha] onto `iconOpacity`; [shineAlpha] drives an optional light/spark
 * overlay where the surface supports one.
 *
 * @property scale icon size multiplier (1 = the marker's natural size).
 * @property rotationDegrees clockwise rotation to apply, in degrees.
 * @property shineAlpha 0..1 strength of the spawn light / despawn spark, 0 when none.
 * @property contentAlpha 0..1 opacity of the crown glyph itself.
 */
data class CrownAnimationState(
    val id: String,
    val phase: CrownAnimationPhase,
    val scale: Float,
    val rotationDegrees: Float,
    val shineAlpha: Float,
    val contentAlpha: Float,
)
