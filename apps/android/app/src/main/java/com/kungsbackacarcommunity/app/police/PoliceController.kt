package com.kungsbackacarcommunity.app.police

import android.content.Context
import com.kungsbackacarcommunity.app.incidents.ViewportRadius
import com.kungsbackacarcommunity.app.navigation.CurrentLocation
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of a police report, so the UI can show the right feedback. */
sealed interface PoliceReportOutcome {
    data object Success : PoliceReportOutcome

    /** No device location was available (permission off / no fix). */
    data object NoLocation : PoliceReportOutcome

    data class Failed(val cause: Throwable) : PoliceReportOutcome
}

/** Outcome of a confirm/dispute verify action, so the UI can show the right feedback. */
sealed interface PoliceVerifyOutcome {
    /** [alreadyVoted] lets the UI say "you already verified this" vs "thanks". */
    data class Success(val alreadyVoted: Boolean) : PoliceVerifyOutcome

    data class Failed(val cause: Throwable) : PoliceVerifyOutcome
}

/**
 * The small police-pin API surfaced to the map shell: keep a live list of nearby
 * police pins to draw + drive the proximity alert, and report a pin at the user's
 * current location. Modelled on
 * [com.kungsbackacarcommunity.app.incidents.IncidentReportController] (same poll
 * shape, same best-effort semantics), deliberately leaner because a police pin
 * has no votes/removal/notes.
 *
 * The location source is injected ([locationProvider]) so the controller is
 * JVM-unit-testable with a fake provider + fake repository; on device the factory
 * wires it to the fused one-shot ([CurrentLocation]).
 */
class PoliceController(
    private val repository: PoliceRepository,
    private val locationProvider: suspend () -> LatLng?,
) {
    private val nearbyFlow = MutableStateFlow<List<PoliceReport>>(emptyList())

    /** Active police pins near the last refreshed centre, for the map + alerts. */
    val nearbyPolice: StateFlow<List<PoliceReport>> = nearbyFlow.asStateFlow()

    /**
     * Reports a police pin at the caller's CURRENT location. Returns
     * [PoliceReportOutcome.NoLocation] when no fix is available (nothing sent).
     * On success the reporter's own pin is added to [nearbyPolice] optimistically
     * (id-keyed), so it appears without waiting for the next poll.
     */
    suspend fun report(source: String = PoliceRepository.SOURCE_MANUAL): PoliceReportOutcome {
        val here =
            try {
                locationProvider()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            } ?: return PoliceReportOutcome.NoLocation
        return submit(here, source)
    }

    /** Reports at an EXPLICIT location (e.g. a convoy reaction's own fix). */
    suspend fun reportAt(location: LatLng, source: String = PoliceRepository.SOURCE_CONVOY): PoliceReportOutcome {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) {
            return PoliceReportOutcome.NoLocation
        }
        return submit(location, source)
    }

    private suspend fun submit(here: LatLng, source: String): PoliceReportOutcome {
        return try {
            val reported = repository.report(here, source)
            // Optimistic, id-keyed add so a poll that already returned it is not
            // doubled up and a failed poll cannot drop the reporter's own pin.
            nearbyFlow.value = nearbyFlow.value.filterNot { it.id == reported.id } + reported
            PoliceReportOutcome.Success
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            PoliceReportOutcome.Failed(error)
        }
    }

    /**
     * Removes the caller's OWN pin [policeReportId] (`police.remove`) and drops it
     * from [nearbyPolice] so the marker + any open sheet disappear at once. Returns
     * true on success (including the idempotent no-op for an already-gone pin);
     * false on failure, leaving the pin in place so the user can retry.
     */
    suspend fun remove(policeReportId: String): Boolean {
        return try {
            repository.remove(policeReportId)
            nearbyFlow.value = nearbyFlow.value.filterNot { it.id == policeReportId }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Confirms someone else's pin [policeReportId] is still there and reflects the
     * new tallies on [nearbyPolice], so an open sheet updates without a refetch.
     * The local counts are patched only AFTER the backend accepts.
     */
    suspend fun confirm(policeReportId: String): PoliceVerifyOutcome = verify(policeReportId) {
        repository.confirm(policeReportId)
    }

    /**
     * Disputes someone else's pin [policeReportId] ("Borta/Not here") and reflects
     * the new tallies on [nearbyPolice]. A dispute informs only — the pin is never
     * dropped here, only its counts patched.
     */
    suspend fun dispute(policeReportId: String): PoliceVerifyOutcome = verify(policeReportId) {
        repository.dispute(policeReportId)
    }

    private suspend fun verify(
        policeReportId: String,
        call: suspend () -> PoliceVerifyResult,
    ): PoliceVerifyOutcome {
        return try {
            val result = call()
            nearbyFlow.value =
                nearbyFlow.value.map { pin ->
                    if (pin.id == policeReportId) {
                        // Both counts move together: a confirm can SWITCH the
                        // caller's earlier dispute (and vice versa), so patching one
                        // count alone would leave the other stale until the next poll.
                        pin.copy(
                            confirmationCount = result.confirmationCount,
                            disputeCount = result.disputeCount,
                        )
                    } else {
                        pin
                    }
                }
            PoliceVerifyOutcome.Success(alreadyVoted = result.alreadyVoted)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            PoliceVerifyOutcome.Failed(error)
        }
    }

    /**
     * Refreshes [nearbyPolice] around [center]. Failures leave the previous list
     * intact (the map keeps its last-known pins) rather than clearing it.
     */
    suspend fun refresh(
        center: LatLng,
        radiusMeters: Double = PoliceRepository.DEFAULT_RADIUS_METERS,
    ) {
        val fetched =
            try {
                repository.listNearby(center, radiusMeters)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return
            }
        nearbyFlow.value = fetched
    }

    private suspend fun refreshAround(
        radiusMeters: Double,
        centerProvider: suspend () -> LatLng?,
    ): Boolean {
        val center =
            try {
                centerProvider()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            } ?: return false
        refresh(center, radiusMeters)
        return true
    }

    /**
     * Keeps [nearbyPolice] LIVE by refreshing around a provided centre on a
     * cadence (the map camera centre in the app wiring), until cancelled. Same
     * two-phase shape as the incident poll: a short cold-open acquisition, then a
     * keep-alive that also wakes on a camera-idle [requeryTicks] pulse. Every pass
     * is best-effort; a failed fetch keeps the last-known pins.
     */
    suspend fun pollNearby(
        radiusProvider: suspend () -> Double? = { PoliceRepository.DEFAULT_RADIUS_METERS },
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        initialRetryMs: Long = DEFAULT_INITIAL_RETRY_MS,
        initialAttempts: Int = DEFAULT_INITIAL_ATTEMPTS,
        centerProvider: suspend () -> LatLng? = locationProvider,
        requeryTicks: ReceiveChannel<Unit>? = null,
    ) {
        require(pollIntervalMs > 0) { "pollIntervalMs must be > 0, was $pollIntervalMs" }
        require(initialRetryMs > 0) { "initialRetryMs must be > 0, was $initialRetryMs" }
        var acquired = false
        var attempt = 0
        while (attempt < initialAttempts && !acquired) {
            acquired = refreshAround(resolveRadius(radiusProvider), centerProvider)
            attempt += 1
            if (!acquired && attempt < initialAttempts) delay(initialRetryMs)
        }
        while (true) {
            if (requeryTicks == null) {
                delay(pollIntervalMs)
            } else {
                val tick = withTimeoutOrNull(pollIntervalMs) { requeryTicks.receiveCatching() }
                if (tick?.isClosed == true) return
            }
            refreshAround(resolveRadius(radiusProvider), centerProvider)
        }
    }

    private suspend fun resolveRadius(radiusProvider: suspend () -> Double?): Double {
        val raw =
            try {
                radiusProvider()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
        val finite =
            if (raw != null && raw.isFinite()) raw else PoliceRepository.DEFAULT_RADIUS_METERS
        return finite.coerceIn(ViewportRadius.MIN_RADIUS_METERS, ViewportRadius.MAX_RADIUS_METERS)
    }

    companion object {
        /** Keep-alive cadence — matches the incident layer (surfaces new pins). */
        const val DEFAULT_POLL_INTERVAL_MS = 15_000L
        const val DEFAULT_INITIAL_RETRY_MS = 3_000L
        const val DEFAULT_INITIAL_ATTEMPTS = 5

        /**
         * Wires a controller to the Firebase repository + the fused one-shot
         * location source, or null when Firebase is not configured (config-less /
         * CI builds simply omit the police layer + proximity alert).
         */
        fun createIfAvailable(context: Context): PoliceController? {
            val repository = FirebasePoliceRepository.createIfAvailable(context) ?: return null
            val appContext = context.applicationContext
            return PoliceController(
                repository = repository,
                locationProvider = { CurrentLocation.lastKnown(appContext) },
            )
        }
    }
}
