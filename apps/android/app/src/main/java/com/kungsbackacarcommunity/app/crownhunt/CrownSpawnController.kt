package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Where the map camera is, for a crown refresh. */
data class CrownQueryCenter(val latitude: Double, val longitude: Double)

/** UI-facing status of a collect attempt. */
sealed interface CrownClaimStatus {
    data object Idle : CrownClaimStatus

    /** The callable is in flight; the Collect button is disabled. */
    data object Collecting : CrownClaimStatus

    /**
     * No usable position, or no second fix yet to prove the member is dwelling.
     * Not a refusal — the app simply cannot make the request yet.
     */
    data object NeedsPosition : CrownClaimStatus

    /** The callable answered — with an award or an honest refusal. */
    data class Done(val outcome: CrownSpawnClaimOutcome, val spawnId: String) : CrownClaimStatus

    /** Transport/auth failure. Says "something went wrong", never judges the user. */
    data object Failed : CrownClaimStatus
}

/**
 * Keeps the crown layer live around the visible map and runs the collect flow.
 *
 * ## The flag gate is structural, not cosmetic
 *
 * `crownHuntSpawn` defaults OFF, and "off" here means **no queries at all** —
 * not a hidden layer that still costs reads. [enabledProvider] is consulted at
 * the top of every single refresh, before the query plan is built and before the
 * repository is touched, so a flag that flips off mid-session stops the traffic
 * on the next pass rather than at the next tab change. [pollNearby] also clears
 * [nearbySpawns] on the way out, so a disabled feature cannot leave stale crowns
 * painted on the map.
 *
 * The map host ALSO gates the whole effect on the flag, so in practice this
 * controller is not even running when the feature is off. Both exist on purpose:
 * the host gate is the one that saves the coroutine, and this one is the one a
 * unit test can prove.
 *
 * ## Battery and data
 *
 * Deliberately the same shape and the same cadence as
 * [com.kungsbackacarcommunity.app.incidents.IncidentReportController.pollNearby]
 * — camera-idle pulses coalesced with a slow keep-alive — rather than a second,
 * differently-tuned polling mechanism competing with it:
 *
 *  - **Camera-idle** re-query is what makes the layer feel immediate, and it is
 *    free when the user is not moving the map.
 *  - **[DEFAULT_POLL_INTERVAL_MS] = 60 s** keep-alive, four times slower than
 *    the incidents layer's 15 s. That is the right trade for this data: the
 *    spawner runs every 10 minutes and the shortest crown TTL is 6 hours, so
 *    there is nothing a faster poll could surface. A parked phone with the map
 *    open therefore costs one small indexed query a minute.
 *  - **[CrownSpawnQuery.shouldRequery]** drops a settle that lands on the same
 *    cells, so nudging the map around a car park costs nothing.
 *  - The query itself is bounded at [CrownSpawnQuery.MAX_CELLS] cells (a
 *    town-sized 11x11 block, fanned out across at most
 *    [CrownSpawnQuery.MAX_BATCHES] parallel `in` queries) and
 *    [CrownSpawnRepository.MAX_SPAWNS_PER_QUERY] documents.
 *
 * Pure-ish Kotlin: the repository, the clock, the centre and the flag are all
 * injected, so the gating and the cadence are unit-tested without a device.
 */
class CrownSpawnController(
    private val repository: CrownSpawnRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val nearbyFlow = MutableStateFlow<List<CrownSpawn>>(emptyList())

    /** Live crowns around the last refreshed centre, for the map layer. */
    val nearbySpawns: StateFlow<List<CrownSpawn>> = nearbyFlow.asStateFlow()

    private val claimFlow = MutableStateFlow<CrownClaimStatus>(CrownClaimStatus.Idle)
    val claimStatus: StateFlow<CrownClaimStatus> = claimFlow.asStateFlow()

    /** The cells the last successful refresh covered, so a settle can be skipped. */
    private var lastCellKeys: List<String>? = null

    /**
     * One refresh pass. Returns true when a query was actually issued.
     *
     * Returns false — having touched nothing — when the feature is off, when no
     * centre is available yet, or when the settled camera covers the same cells
     * as last time.
     */
    suspend fun refreshOnce(
        enabled: Boolean,
        center: CrownQueryCenter?,
        visibleRadiusMeters: Double?,
        force: Boolean = false,
    ): Boolean {
        if (!enabled) {
            // Off means off: drop whatever is on the map as well as issuing no
            // query, so flipping the flag takes the crowns down rather than
            // freezing them there.
            clear()
            return false
        }
        if (center == null) return false
        val keys =
            CrownSpawnQuery.cellKeysFor(
                centerLat = center.latitude,
                centerLon = center.longitude,
                visibleRadiusMeters = visibleRadiusMeters,
            )
        if (keys.isEmpty()) return false
        if (!force && !CrownSpawnQuery.shouldRequery(lastCellKeys, keys)) return false
        return try {
            val spawns = repository.listNearby(keys, nowMillis())
            nearbyFlow.value = spawns
            lastCellKeys = keys
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Keep the last-known crowns. A transient outage must never blank the
            // layer — the same posture the incidents layer takes, and for the
            // same reason: an empty map is indistinguishable from "no crowns
            // here", which is a lie the user cannot detect.
            false
        }
    }

    /**
     * Keeps [nearbySpawns] live until the coroutine is cancelled.
     *
     * Mirrors the incident layer's two-phase loop: a short cold-open acquisition
     * burst so the layer is not empty for the first minute, then a steady state
     * that waits for EITHER a camera-idle tick or [pollIntervalMs], whichever
     * lands first, restarting the wait after every refresh so the two can never
     * double-fire.
     *
     * [enabledProvider] is read on every pass, including inside phase 1 — a
     * disabled feature spins no queries at all, and no timer either, because the
     * loop simply returns.
     */
    suspend fun pollNearby(
        enabledProvider: suspend () -> Boolean,
        centerProvider: suspend () -> CrownQueryCenter?,
        radiusProvider: suspend () -> Double?,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        initialRetryMs: Long = DEFAULT_INITIAL_RETRY_MS,
        initialAttempts: Int = DEFAULT_INITIAL_ATTEMPTS,
        requeryTicks: ReceiveChannel<Unit>? = null,
    ) {
        // Misuse fails fast rather than shipping a delay(0) hot loop that drains
        // the battery — same guard as the incidents poll.
        require(pollIntervalMs > 0) { "pollIntervalMs must be > 0, was $pollIntervalMs" }
        require(initialRetryMs > 0) { "initialRetryMs must be > 0, was $initialRetryMs" }

        // Phase 1: acquire a first centre quickly.
        var acquired = false
        var attempt = 0
        while (attempt < initialAttempts && !acquired) {
            if (!readEnabled(enabledProvider)) {
                clear()
                return
            }
            acquired =
                refreshOnce(
                    enabled = true,
                    center = centerProvider(),
                    visibleRadiusMeters = radiusProvider(),
                    // The very first pass must draw even though no cells have
                    // been queried yet; shouldRequery would allow it anyway, but
                    // saying so here makes the intent explicit.
                    force = attempt == 0,
                )
            attempt += 1
            if (!acquired && attempt < initialAttempts) delay(initialRetryMs)
        }

        // Phase 2: keep the layer live.
        while (true) {
            if (requeryTicks == null) {
                delay(pollIntervalMs)
            } else {
                // Conflated channel: a pulse sent while a refresh was in flight
                // is delivered here rather than dropped. receiveCatching (not
                // receive) so a CLOSED channel — a normal teardown — hands back a
                // closed result instead of throwing.
                val tick = withTimeoutOrNull(pollIntervalMs) { requeryTicks.receiveCatching() }
                if (tick?.isClosed == true) return
            }
            if (!readEnabled(enabledProvider)) {
                clear()
                return
            }
            refreshOnce(
                enabled = true,
                center = centerProvider(),
                visibleRadiusMeters = radiusProvider(),
            )
        }
    }

    /**
     * Collects [spawn].
     *
     * The stationary PROOF is the caller's two fixes. This does the arithmetic
     * the server would do first ([CrownCollectGate.isDwellProofUsable]) so an
     * unusable pair becomes "wait a moment" locally instead of a round-trip that
     * comes back `must_be_stationary` — which would read as a refusal when it
     * was really just an impatient tap.
     *
     * On a successful award the crown is removed from [nearbySpawns] immediately.
     * It is claimed once GLOBALLY, so it is gone for everyone; waiting for the
     * next refresh to notice would leave a collectable-looking marker on the map
     * for up to a minute.
     */
    suspend fun collect(
        spawn: CrownSpawn,
        current: CrownFix?,
        previous: CrownFix?,
        idempotencyKey: String,
    ) {
        if (claimFlow.value == CrownClaimStatus.Collecting) return
        if (current == null || previous == null ||
            !CrownCollectGate.isDwellProofUsable(previous, current)
        ) {
            claimFlow.value = CrownClaimStatus.NeedsPosition
            return
        }
        claimFlow.value = CrownClaimStatus.Collecting
        try {
            val outcome = repository.claimSpawn(spawn.id, current, previous, idempotencyKey)
            if (outcome.result == CrownSpawnClaimResult.AWARDED ||
                outcome.result == CrownSpawnClaimResult.ALREADY_TAKEN ||
                outcome.result == CrownSpawnClaimResult.CROWN_EXPIRED
            ) {
                // All three mean the crown is no longer collectable by anyone.
                // Dropping it on "already taken" is the difference between a
                // graceful "someone beat you to it" and a marker that invites
                // the user to try again for something that no longer exists.
                dropSpawn(spawn.id)
            }
            claimFlow.value = CrownClaimStatus.Done(outcome, spawn.id)
        } catch (cancellation: CancellationException) {
            claimFlow.value = CrownClaimStatus.Idle
            throw cancellation
        } catch (_: Exception) {
            claimFlow.value = CrownClaimStatus.Failed
        }
    }

    /** Clears the last result/failure so the button is usable again. */
    fun resetClaim() {
        claimFlow.value = CrownClaimStatus.Idle
    }

    /** Drops every crown and forgets the query anchor. */
    fun clear() {
        nearbyFlow.value = emptyList()
        lastCellKeys = null
    }

    private fun dropSpawn(spawnId: String) {
        nearbyFlow.value = nearbyFlow.value.filterNot { it.id == spawnId }
    }

    /**
     * Reads the flag, treating a FAILURE as "off".
     *
     * The opposite of how [com.kungsbackacarcommunity.app.config.FeatureFlagsStore]
     * treats flags in general ("flags never fail off"), and deliberately so:
     * that rule protects features whose default is ON from a config outage,
     * whereas this one's contract default is OFF. Failing it open would start
     * querying a feature nobody switched on.
     */
    private suspend fun readEnabled(enabledProvider: suspend () -> Boolean): Boolean =
        try {
            enabledProvider()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }

    companion object {
        /**
         * Steady-state keep-alive. 60 s, four times slower than the incidents
         * layer's 15 s: the spawner runs every 10 minutes and the shortest crown
         * lives 6 hours, so there is nothing for a faster poll to find. Camera
         * idle covers the case that actually matters (the user moved the map).
         */
        const val DEFAULT_POLL_INTERVAL_MS = 60_000L

        /** Cold-open backoff + attempt budget, mirroring the incidents layer. */
        const val DEFAULT_INITIAL_RETRY_MS = 3_000L
        const val DEFAULT_INITIAL_ATTEMPTS = 5

        /**
         * Wires a controller to Firebase, or returns null when Firebase is not
         * configured — so a config-less build simply has no crown layer.
         */
        fun createIfAvailable(context: Context): CrownSpawnController? {
            val repository = FirebaseCrownSpawnRepository.createIfAvailable(context) ?: return null
            return CrownSpawnController(repository)
        }
    }
}
