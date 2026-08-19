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
 *  - The query is bounded at [CrownSpawnQuery.MAX_CELLS] cells (a town-sized
 *    11x11 block), fanned out across at most [CrownSpawnQuery.MAX_BATCHES]
 *    parallel `in` queries. Two different caps apply, and they are not the same
 *    number: each batch carries its own `limit([CrownSpawnRepository.MAX_SPAWNS_PER_QUERY])`,
 *    so the worst-case READ cost of one refresh is
 *    [CrownSpawnQuery.MAX_BATCHES] x [CrownSpawnRepository.MAX_SPAWNS_PER_QUERY]
 *    documents, whereas the DRAWN crowns are capped at
 *    [CrownSpawnRepository.MAX_SPAWNS_PER_QUERY] after the batches are merged and
 *    deduped. In practice the spawner's density budget keeps a town far under the
 *    read ceiling; the ceiling exists so a bug or retune cannot make one pan
 *    unbounded.
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

    /**
     * Spawn IDs THIS user has already collected, mapped to the crown's own expiry
     * (nullable when the document omitted it).
     *
     * Only SHARED crowns land here: an exclusive crown is gone for everyone the
     * moment it is claimed, so `listNearby` never returns it again. A shared crown
     * stays `live` for OTHER members, so `listNearby` keeps returning it — and the
     * member who already picked it up keeps seeing the very same crown.
     *
     * Rather than HIDE it (#874's original fix, which filtered the query result
     * against this set), the crown is KEPT on the map and drawn with a DISTINCT
     * "collected by you" marker: this set is what [collectedSpawnIds] exposes, so
     * the map layer can stamp the check badge on exactly these crowns while they
     * stay collectable-looking for everyone else. Keyed to the expiry so it
     * self-prunes rather than growing for the life of the session — once a crown's
     * TTL passes the backend stops returning it AND its entry here is dropped.
     */
    private val collectedSpawnExpiries = mutableMapOf<String, Long?>()

    private val collectedIdsFlow = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The ids of the SHARED crowns this member has already collected but which are
     * still live on the map for others — the crowns the layer draws with the
     * distinct "collected by you" marker. Survives a map refresh (the entries do),
     * and self-prunes as each collected crown expires.
     */
    val collectedSpawnIds: StateFlow<Set<String>> = collectedIdsFlow.asStateFlow()

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
            // Keep crowns this user already collected ON the map — a shared crown
            // they picked up is still `live` for others, so the query returns it
            // every pass, and the layer draws it with the distinct "collected by
            // you" marker rather than hiding it (revises #874). Prune the
            // collected set first so an expired entry stops marking a crown that is
            // gone for everyone anyway.
            pruneCollected()
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
            when (outcome.result) {
                // The user's FIRST successful collect. What happens next depends on
                // the crown's collect mode, NOT just the result: a SHARED crown
                // (common/uncommon) stays `live` for OTHER members and comes back on
                // the next refresh, so dropping it would make it flicker away and
                // reappear looking collectable — instead it is KEPT and marked
                // collected-by-you, the SAME distinct marker a later re-tap
                // (ALREADY_COLLECTED) shows, so first-collect and re-tap agree. An
                // EXCLUSIVE crown (rare/legendary) is gone for everyone the moment it
                // is claimed, so it is dropped now rather than lingering as a
                // collectable-looking marker until the next refresh notices.
                CrownSpawnClaimResult.AWARDED ->
                    when (spawn.rarity.collectMode) {
                        CrownCollectMode.SHARED -> markCollected(spawn)
                        CrownCollectMode.EXCLUSIVE -> dropSpawn(spawn.id)
                    }
                // Gone for everyone: an exclusive crown someone else already took, or
                // one that expired. Drop it now; the server already hides it from
                // listNearby, so no refresh will re-add it.
                CrownSpawnClaimResult.ALREADY_TAKEN,
                CrownSpawnClaimResult.CROWN_EXPIRED -> dropSpawn(spawn.id)
                // A SHARED crown the user already picked up. It legitimately stays
                // `live` on the map for OTHER members, so it is KEPT on the map and
                // re-drawn with the distinct "collected by you" marker rather than
                // hidden. REMEMBER it (keyed to its expiry) so every refresh keeps
                // marking it as collected until it expires (revises #874). The crown
                // is already in the list, so marking it is enough — no drop.
                CrownSpawnClaimResult.ALREADY_COLLECTED -> markCollected(spawn)
                else -> Unit
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
     * Records that this user has collected [spawn] — a SHARED crown that stays on
     * the map for others — so every subsequent refresh keeps drawing it with the
     * distinct "collected by you" marker. Keyed to the crown's own expiry so the
     * entry self-prunes once the crown is gone for everyone.
     */
    private fun markCollected(spawn: CrownSpawn) {
        collectedSpawnExpiries[spawn.id] = spawn.expiresAtMillis
        pruneCollected()
    }

    /**
     * Prunes collected entries whose crown has since expired so the set never
     * grows without bound, then republishes [collectedSpawnIds].
     *
     * Lifecycle: an entry with a known expiry is dropped once that expiry passes.
     * An entry with a null expiry (the document omitted one) is kept for the rest
     * of the SESSION — there is no known moment at which it is safe to forget,
     * since the crown could still be live. Nothing clears the collected set on the
     * way out: [clear] deliberately resets only [nearbySpawns] and the query
     * anchor, so a member who leaves the map (or toggles the feature) and comes
     * back still sees their collected crowns marked rather than looking
     * collectable again. The set is process-lifetime and bounded by the distinct
     * shared crowns a member collects before the app is next killed.
     */
    private fun pruneCollected() {
        if (collectedSpawnExpiries.isNotEmpty()) {
            val now = nowMillis()
            collectedSpawnExpiries.entries.removeAll { (_, expiry) -> expiry != null && expiry <= now }
        }
        val ids = collectedSpawnExpiries.keys.toSet()
        if (ids != collectedIdsFlow.value) collectedIdsFlow.value = ids
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
