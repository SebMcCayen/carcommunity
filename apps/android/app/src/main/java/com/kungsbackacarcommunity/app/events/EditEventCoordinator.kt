package com.kungsbackacarcommunity.app.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of an in-flight creator edit (events-update) write. */
sealed interface EditEventStatusUi {
    data object Idle : EditEventStatusUi

    data object Saving : EditEventStatusUi

    /** The edit was saved for [eventId]; the detail listener refreshes it. */
    data class Success(val eventId: String) : EditEventStatusUi

    /**
     * The edit failed, carrying the domain [reason] so the form can say something
     * true — a [ManageEventFailure.PERMISSION_DENIED] (not the creator) or a
     * [ManageEventFailure.IMMUTABLE] (the event became cancelled/completed) is not
     * a "try again", so each gets its own message.
     */
    data class Failed(val reason: ManageEventFailure) : EditEventStatusUi
}

/**
 * Orchestrates a creator's edit-event (events-update) write. Pure Kotlin (no
 * Firebase/Android types) so it is unit-testable with a fake repository. Mirrors
 * [CreateEventCoordinator] — same double-submit guard and observable status —
 * and REUSES the same pure validation ([Events.isValidForCreate]) the create
 * form gates on, since the edit form produces the same [CreateEventInput].
 */
class EditEventCoordinator(
    private val repository: EventsRepository,
) {
    private val state = MutableStateFlow<EditEventStatusUi>(EditEventStatusUi.Idle)
    val status: StateFlow<EditEventStatusUi> = state.asStateFlow()

    suspend fun submit(eventId: String, input: CreateEventInput) {
        if (state.value == EditEventStatusUi.Saving) return
        if (eventId.isBlank() || !Events.isValidForCreate(input)) {
            state.value = EditEventStatusUi.Failed(ManageEventFailure.UNKNOWN)
            return
        }
        state.value = EditEventStatusUi.Saving
        try {
            repository.updateEvent(eventId, input)
            state.value = EditEventStatusUi.Success(eventId)
        } catch (cancellation: CancellationException) {
            state.value = EditEventStatusUi.Idle
            throw cancellation
        } catch (failure: Exception) {
            // A repository that classified the failure (the Firebase one maps the
            // callable's permission-denied / failed-precondition onto the domain
            // reason) wins; any other exception is an honest UNKNOWN.
            state.value =
                EditEventStatusUi.Failed(
                    (failure as? UpdateEventException)?.reason ?: ManageEventFailure.UNKNOWN,
                )
        }
    }

    /** Clears a failure (or a consumed success) so the form is usable again. */
    fun reset() {
        state.value = EditEventStatusUi.Idle
    }
}
