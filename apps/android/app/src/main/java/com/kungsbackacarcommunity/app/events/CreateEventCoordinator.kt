package com.kungsbackacarcommunity.app.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of an in-flight create-event write. */
sealed interface CreateEventStatusUi {
    data object Idle : CreateEventStatusUi

    data object Saving : CreateEventStatusUi

    /** The event was created; [eventId] is the new teaser-doc id. */
    data class Success(val eventId: String) : CreateEventStatusUi

    /**
     * The create failed. For a non-admin caller this is expected today: the
     * `events-create` callable is admin-only, so the write is denied. The form
     * surfaces a generic error; enabling user-created events needs a backend
     * change (a member-callable createEvent + rules), which is out of the
     * Android lane.
     */
    data object Failed : CreateEventStatusUi
}

/**
 * Orchestrates a create-event write. Pure Kotlin (no Firebase/Android types) so
 * it is unit-testable with a fake repository. Mirrors [RsvpCoordinator]: it
 * guards against double-submits and exposes an observable status the form maps
 * to buttons/snackbars.
 */
class CreateEventCoordinator(
    private val repository: EventsRepository,
) {
    private val state = MutableStateFlow<CreateEventStatusUi>(CreateEventStatusUi.Idle)
    val status: StateFlow<CreateEventStatusUi> = state.asStateFlow()

    suspend fun submit(input: CreateEventInput) {
        if (state.value == CreateEventStatusUi.Saving) return
        if (!Events.isValidForCreate(input)) {
            state.value = CreateEventStatusUi.Failed
            return
        }
        state.value = CreateEventStatusUi.Saving
        try {
            val eventId = repository.createEvent(input)
            state.value = CreateEventStatusUi.Success(eventId)
        } catch (cancellation: CancellationException) {
            state.value = CreateEventStatusUi.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = CreateEventStatusUi.Failed
        }
    }

    /** Clears a failure (or a consumed success) so the form is usable again. */
    fun reset() {
        state.value = CreateEventStatusUi.Idle
    }
}
