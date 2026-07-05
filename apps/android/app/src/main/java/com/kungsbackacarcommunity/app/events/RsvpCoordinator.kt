package com.kungsbackacarcommunity.app.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of an in-flight RSVP write. */
sealed interface RsvpStatusUi {
    data object Idle : RsvpStatusUi

    data object Saving : RsvpStatusUi

    data object Failed : RsvpStatusUi
}

/**
 * Orchestrates an RSVP write (Phase 12 slice 9). Pure Kotlin (no Firebase/
 * Android types) so it is unit-testable with a fake repository. The observed
 * RSVP document drives the selected answer; this only tracks the write.
 */
class RsvpCoordinator(
    private val repository: EventsRepository,
) {
    private val state = MutableStateFlow<RsvpStatusUi>(RsvpStatusUi.Idle)
    val status: StateFlow<RsvpStatusUi> = state.asStateFlow()

    suspend fun submit(eventId: String, uid: String, answer: RsvpStatus) {
        if (state.value == RsvpStatusUi.Saving) return
        state.value = RsvpStatusUi.Saving
        try {
            repository.setRsvp(eventId, uid, answer)
            state.value = RsvpStatusUi.Idle
        } catch (cancellation: CancellationException) {
            state.value = RsvpStatusUi.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = RsvpStatusUi.Failed
        }
    }

    /** Clears a failure so the buttons are usable again. */
    fun reset() {
        if (state.value == RsvpStatusUi.Failed) {
            state.value = RsvpStatusUi.Idle
        }
    }
}
