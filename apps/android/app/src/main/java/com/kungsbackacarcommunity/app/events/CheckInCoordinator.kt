package com.kungsbackacarcommunity.app.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a check-in could not be completed, in domain terms (drives the message). */
enum class CheckInError {
    /** Too far from the event's location. */
    OUTSIDE_GEOFENCE,

    /** Check-in has not opened yet, or has already closed. */
    WINDOW_CLOSED,

    /** No usable GPS fix (permission denied, no signal, or the fix was too old). */
    POSITION_UNAVAILABLE,

    /** The fix was reported as mocked, or the backend rejected it as spoofed. */
    MOCK_LOCATION,

    /** The event itself is not open for check-in (draft/cancelled/no coordinates). */
    NOT_CHECKINABLE,

    /** Anything else — offline, backend fault, unrecognised result. Retryable. */
    GENERIC,
}

/** UI-facing state of the check-in flow. */
sealed interface CheckInUiState {
    data object Idle : CheckInUiState

    /** Acquiring the one-shot GPS fix or submitting it — the button shows a spinner. */
    data object Working : CheckInUiState

    /**
     * The check-in was accepted. [verified] true = attendance is proven (show the
     * confirmed state); false = recorded but not yet complete (the geofence+dwell
     * evidence needs a second sample about ten minutes later — prompt to check in
     * again).
     */
    data class Success(val verified: Boolean) : CheckInUiState

    data class Failed(val error: CheckInError) : CheckInUiState
}

/** One-shot position source for a check-in. Injected so the coordinator stays testable. */
fun interface CheckInLocationSource {
    /** A fresh fix, or null when none is available (no permission / no signal). */
    suspend fun currentFix(): CheckInFix?
}

/**
 * Orchestrates a geofenced check-in (window gate → one-shot location → callable
 * → result mapping). Pure Kotlin (no Firebase/Android types) so it is
 * unit-testable with a fake repository and a fake location source.
 *
 * The gates are layered exactly as trust decreases: the WINDOW is checked
 * locally first (cheap, and the button should not even try outside it); the
 * MOCK flag is refused before any network round-trip (an honest client never
 * submits a fix it knows is mocked); and everything that actually decides
 * attendance — the geofence, the dwell, the full anti-fraud pipeline — is the
 * server's, so a doctored client gains nothing by skipping the local checks.
 */
class CheckInCoordinator(
    private val repository: EventsRepository,
    private val locationSource: CheckInLocationSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow<CheckInUiState>(CheckInUiState.Idle)
    val status: StateFlow<CheckInUiState> = state.asStateFlow()

    private val firstFixAt = MutableStateFlow<Long?>(null)

    /**
     * The capture time of the FIRST recorded (in-geofence) fix this session, or
     * null before one lands / after verification. It seeds the dwell countdown
     * immediately, before the persisted attendance record's `createdAt` has
     * round-tripped back through the snapshot listener (the record can lag the
     * callable's reply by a snapshot). It is DELIBERATELY not cleared by a later
     * failed tap — a member who briefly leaves the fence and taps must not see
     * the countdown restart; only the FINAL fix has to be inside.
     */
    val firstFixAtMillis: StateFlow<Long?> = firstFixAt.asStateFlow()

    /**
     * Runs one check-in attempt for [event]. No-op while another attempt is in
     * flight. Never throws (except on cancellation): every failure resolves to a
     * [CheckInUiState.Failed] with a domain [CheckInError].
     */
    suspend fun checkIn(event: EventSummary) {
        if (state.value == CheckInUiState.Working) return

        val now = clock()
        if (!EventCheckIn.isWindowOpen(event, now)) {
            state.value = CheckInUiState.Failed(CheckInError.WINDOW_CLOSED)
            return
        }

        state.value = CheckInUiState.Working
        try {
            val fix = locationSource.currentFix()
            if (fix == null) {
                state.value = CheckInUiState.Failed(CheckInError.POSITION_UNAVAILABLE)
                return
            }
            // Refuse a self-reported mock BEFORE the round-trip — clearer for the
            // member than a generic backend rejection. Defence in depth only: the
            // fix is ALSO sent with its isMock flag, so the server rejects a
            // doctored client that skips this too (checkIn.ts → mockLocationReported).
            if (fix.isMock) {
                state.value = CheckInUiState.Failed(CheckInError.MOCK_LOCATION)
                return
            }

            val result = repository.checkIn(event.id, fix)
            state.value = mapResult(result)
            when (result) {
                // First in-geofence sample landed — anchor the countdown at this
                // fix's own capture time (matching the `capturedAt` the server
                // stored), but only if one is not already running: a second
                // "recorded" reply must not push the countdown forward.
                CheckInResult.RECORDED ->
                    if (firstFixAt.value == null) firstFixAt.value = fix.capturedAtMillis
                // Proven — the countdown has done its job and is retired.
                CheckInResult.VERIFIED, CheckInResult.ALREADY_VERIFIED ->
                    firstFixAt.value = null
                // Every other result (including OUTSIDE_GEOFENCE from a temporary
                // excursion) leaves the countdown exactly as it was.
                else -> Unit
            }
        } catch (cancellation: CancellationException) {
            state.value = CheckInUiState.Idle
            throw cancellation
        } catch (_: Exception) {
            state.value = CheckInUiState.Failed(CheckInError.GENERIC)
        }
    }

    /** Clears a failure so the button is usable again. Leaves a success intact. */
    fun reset() {
        if (state.value is CheckInUiState.Failed) {
            state.value = CheckInUiState.Idle
        }
    }

    private fun mapResult(result: CheckInResult): CheckInUiState =
        when (result) {
            CheckInResult.RECORDED -> CheckInUiState.Success(verified = false)
            CheckInResult.VERIFIED, CheckInResult.ALREADY_VERIFIED ->
                CheckInUiState.Success(verified = true)
            CheckInResult.OUTSIDE_GEOFENCE -> CheckInUiState.Failed(CheckInError.OUTSIDE_GEOFENCE)
            CheckInResult.OUTSIDE_WINDOW -> CheckInUiState.Failed(CheckInError.WINDOW_CLOSED)
            CheckInResult.EVENT_NOT_CHECKINABLE -> CheckInUiState.Failed(CheckInError.NOT_CHECKINABLE)
            CheckInResult.POSITION_TOO_OLD -> CheckInUiState.Failed(CheckInError.POSITION_UNAVAILABLE)
            // The server never says WHICH signal tripped (telling a client is
            // telling a cheat what to change), so risk_review is surfaced as the
            // location-could-not-be-verified message rather than a specific one.
            CheckInResult.RISK_REVIEW -> CheckInUiState.Failed(CheckInError.MOCK_LOCATION)
            CheckInResult.UNKNOWN -> CheckInUiState.Failed(CheckInError.GENERIC)
        }
}
