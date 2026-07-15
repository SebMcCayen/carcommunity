package com.kungsbackacarcommunity.app.incidents

import android.content.Context
import com.kungsbackacarcommunity.app.navigation.CurrentLocation
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CancellationException
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
     * [ReportOutcome.NoLocation] when no fix is available (nothing is sent), and
     * refreshes the nearby list on success so the new marker appears at once.
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
            repository.report(type, here, note)
            // refresh() handles its own non-cancellation failures internally, so a
            // failed refresh never fails the report; cancellation still propagates.
            refresh(here)
            ReportOutcome.Success
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            ReportOutcome.Failed(error)
        }
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

    companion object {
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
