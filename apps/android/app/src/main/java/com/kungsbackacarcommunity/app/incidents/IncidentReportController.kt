package com.kungsbackacarcommunity.app.incidents

import android.content.Context
import com.kungsbackacarcommunity.app.navigation.CurrentLocation
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Outcome of a report attempt, so the UI can show the right feedback. */
sealed interface ReportOutcome {
    /** The incident was reported. */
    data object Success : ReportOutcome

    /** No device location was available (permission off / no fix). */
    data object NoLocation : ReportOutcome

    /** The backend call failed. */
    data class Failed(val cause: Throwable) : ReportOutcome
}

/**
 * The small, reusable incidents API surfaced to the map shell AND the sibling
 * turn-by-turn navigation PR: report an incident at the user's current
 * location, and keep a live list of nearby incidents to draw on the map.
 *
 * Deliberately UI-framework-light: it exposes a [nearbyIncidents] StateFlow and
 * two suspend entry points. The navigation feature can hold the same controller
 * instance and call [report] from its own "report" button without depending on
 * the shell.
 *
 * The location source is injected ([locationProvider]) so the controller is
 * JVM-unit-testable with a fake provider + fake repository; on device the
 * factory wires it to the fused-location one-shot ([CurrentLocation]).
 */
class IncidentReportController(
    private val repository: IncidentRepository,
    private val locationProvider: suspend () -> LatLng?,
) {
    private val nearbyFlow = MutableStateFlow<List<Incident>>(emptyList())

    /** Active incidents near the last refreshed centre, for the map layer. */
    val nearbyIncidents: StateFlow<List<Incident>> = nearbyFlow.asStateFlow()

    /**
     * Reports an incident of [type] at the caller's CURRENT location. Returns
     * [ReportOutcome.NoLocation] when no fix is available (nothing is sent).
     *
     * A [ReportOutcome.Success] means the reporter's own marker is IN
     * [nearbyIncidents] — the promise the success message makes ("your report is
     * on the map") is kept by this method, not delegated. The created incident
     * comes back from the write itself and is added here; the follow-up [refresh]
     * is only to pick up anything else that has appeared nearby, and is
     * best-effort by design.
     *
     * That ordering is the whole point. This used to report Success on the
     * strength of the write and leave the pin to [refresh] — a SECOND round-trip
     * (`listNearby`, which unlike the write needs a composite index and an extra
     * read permission) whose failures [refresh] deliberately swallows. Every way
     * that call could fail produced the same silent result: "your report is on
     * the map", and no marker.
     */
    suspend fun report(type: IncidentType, note: String? = null): ReportOutcome {
        // Rethrow cancellation so structured concurrency is honoured; only a real
        // location failure (permission off / no fix) degrades to NoLocation.
        val here =
            try {
                locationProvider()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            } ?: return ReportOutcome.NoLocation
        return try {
            val reported = repository.report(type, here, note)
            // Best-effort sweep for everything else nearby. refresh() handles its
            // own non-cancellation failures internally, so a failed refresh never
            // fails the report; cancellation still propagates.
            refresh(here)
            // The promised marker, applied LAST and from the write alone, so a
            // refresh that succeeded (and replaced the whole list) can't drop it
            // and a refresh that failed can't prevent it. add() is id-keyed, so a
            // refresh that already returned this incident is not doubled up.
            add(reported)
            ReportOutcome.Success
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            ReportOutcome.Failed(error)
        }
    }

    /**
     * Removes the caller's own report [incidentId] and drops it from
     * [nearbyIncidents]. Returns true when the backend accepted the removal.
     *
     * The local drop happens only AFTER the call succeeds, so a rejected removal
     * (the backend refuses anything that is not your own user-sourced report)
     * leaves the marker on the map rather than hiding an incident that is still
     * live for everyone else. Cancellation propagates; any other failure returns
     * false for the caller to surface.
     */
    suspend fun remove(incidentId: String): Boolean {
        return try {
            repository.remove(incidentId)
            nearbyFlow.value = nearbyFlow.value.filterNot { it.id == incidentId }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Adds (or replaces, by id) a single incident in [nearbyIncidents]. Keyed on
     * id so a later fetch that also returns it cannot double it up.
     */
    private fun add(incident: Incident) {
        nearbyFlow.value =
            nearbyFlow.value.filterNot { it.id == incident.id } + incident
    }

    /**
     * Refreshes [nearbyIncidents] around [center]. Failures leave the previous
     * list intact (the map keeps showing the last-known incidents) rather than
     * clearing it.
     */
    suspend fun refresh(center: LatLng, radiusMeters: Double = IncidentRepository.DEFAULT_RADIUS_METERS) {
        val fetched =
            try {
                repository.listNearby(center, radiusMeters)
            } catch (cancellation: CancellationException) {
                // Propagate cancellation instead of silently keeping the old list.
                throw cancellation
            } catch (_: Throwable) {
                // Any real fetch failure leaves the previous list intact.
                return
            }
        nearbyFlow.value = fetched
    }

    /**
     * Convenience for the shell/nav: refresh [nearbyIncidents] around the user's
     * CURRENT location. A no-op when no fix is available (the map keeps the last
     * markers). The nav feature can call this on a cadence as the route moves.
     *
     * Returns `true` when a location fix was available and a refresh was
     * performed, `false` when no fix was available (nothing fetched). Callers
     * that retry a cold-open refresh use this to retry only until a fix arrives,
     * rather than looping on a legitimately empty result.
     */
    suspend fun refreshAroundCurrent(
        radiusMeters: Double = IncidentRepository.DEFAULT_RADIUS_METERS
    ): Boolean {
        val here =
            try {
                locationProvider()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            } ?: return false
        refresh(here, radiusMeters)
        return true
    }

    /**
     * Keeps [nearbyIncidents] LIVE by refreshing around the user's current
     * location on a cadence, until the coroutine is cancelled (the caller scopes
     * it to the Map tab being shown and the layer enabled).
     *
     * This is the fix for "an incident someone else reports isn't visible to
     * me". The incident layer is a SHARED, Waze-style map layer: the reporter's
     * own pin is added optimistically from the write's response, but every OTHER
     * user only ever learns of a report through [IncidentRepository.listNearby]
     * (reached here via [refresh] / [refreshAroundCurrent]). A single fetch on
     * tab-entry left those users looking at a stale layer — a report made while
     * they were already on the map never appeared until they left and came back.
     * Polling closes that gap.
     *
     * Two phases:
     *  1. Cold-open acquisition — the fused last-known location is frequently
     *     null right after launch, so the first few passes retry on a short
     *     [initialRetryMs] backoff until a fix arrives (refreshAroundCurrent
     *     returns true → a refresh ran), so the layer populates ASAP.
     *  2. Steady state — refresh every [pollIntervalMs] so newly-reported
     *     incidents from other users keep appearing. A pass with no fix is a
     *     harmless no-op that the next tick retries, so GPS arriving late still
     *     recovers (unlike the old give-up-after-N-attempts behaviour).
     *
     * Every pass is best-effort: [refreshAroundCurrent] no-ops without a fix and
     * [refresh] swallows fetch failures, keeping the last-known markers — so a
     * transient outage never blanks the map. Cancellation propagates.
     *
     * The delay inputs MUST be strictly positive: a zero/negative [pollIntervalMs]
     * turns phase 2 into a `delay(0)` busy loop that hammers the callable and
     * drains the battery, and a non-positive [initialRetryMs] does the same to
     * the cold-open retry. Misuse fails fast rather than shipping a hot loop.
     */
    suspend fun pollNearby(
        radiusMeters: Double = IncidentRepository.DEFAULT_RADIUS_METERS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        initialRetryMs: Long = DEFAULT_INITIAL_RETRY_MS,
        initialAttempts: Int = DEFAULT_INITIAL_ATTEMPTS,
    ) {
        require(pollIntervalMs > 0) { "pollIntervalMs must be > 0, was $pollIntervalMs" }
        require(initialRetryMs > 0) { "initialRetryMs must be > 0, was $initialRetryMs" }
        // Phase 1: acquire the first fix quickly so the layer is not blank for
        // the whole initial poll interval on a cold open.
        var acquired = false
        var attempt = 0
        while (attempt < initialAttempts && !acquired) {
            acquired = refreshAroundCurrent(radiusMeters)
            attempt += 1
            if (!acquired && attempt < initialAttempts) delay(initialRetryMs)
        }
        // Phase 2: keep the shared layer live for the lifetime of this coroutine.
        while (true) {
            delay(pollIntervalMs)
            refreshAroundCurrent(radiusMeters)
        }
    }

    companion object {
        /**
         * Steady-state cadence for the live incident-layer poll. A shared
         * traffic layer must feel current, but each pass costs a cheap
         * last-known-location read plus one listNearby callable, so 30 s balances
         * freshness against battery and read cost (incident TTLs are ≥ 1 h, so
         * sub-minute latency to surface a fresh report is ample).
         */
        const val DEFAULT_POLL_INTERVAL_MS = 30_000L

        /**
         * Cold-open acquisition backoff + attempt budget: the fused last-known
         * location is frequently null for the first second or two after launch,
         * so retry a few times on a short delay before settling into the steady
         * cadence. Exhausting these is NOT fatal — phase 2 keeps trying — it just
         * bounds how eagerly we spin while waiting for the very first fix.
         */
        const val DEFAULT_INITIAL_RETRY_MS = 3_000L
        const val DEFAULT_INITIAL_ATTEMPTS = 5

        /**
         * Wires a controller to the Firebase repository + the fused one-shot
         * location source, or returns null when Firebase is not configured (so
         * the shell/nav simply omit the incident layer in CI/config-less builds).
         */
        fun createIfAvailable(context: Context): IncidentReportController? {
            val repository = FirebaseIncidentRepository.createIfAvailable(context) ?: return null
            val appContext = context.applicationContext
            return IncidentReportController(
                repository = repository,
                locationProvider = { CurrentLocation.lastKnown(appContext) },
            )
        }
    }
}
