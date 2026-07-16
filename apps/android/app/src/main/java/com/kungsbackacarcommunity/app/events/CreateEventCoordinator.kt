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
     * The create failed, carrying the domain [reason] so the form can say
     * something true — notably [CreateEventFailure.RATE_LIMITED] (the member's
     * 3-per-24h cap), which is a "come back later", not a "try again".
     */
    data class Failed(val reason: CreateEventFailure) : CreateEventStatusUi
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
            state.value = CreateEventStatusUi.Failed(CreateEventFailure.UNKNOWN)
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
            // A repository that classified the failure (the Firebase one maps
            // the callable's `resource-exhausted` onto RATE_LIMITED) wins; any
            // other exception is an honest UNKNOWN.
            state.value =
                CreateEventStatusUi.Failed(
                    (failure as? CreateEventException)?.reason ?: CreateEventFailure.UNKNOWN,
                )
        }
    }

    /** Clears a failure (or a consumed success) so the form is usable again. */
    fun reset() {
        state.value = CreateEventStatusUi.Idle
    }
}
