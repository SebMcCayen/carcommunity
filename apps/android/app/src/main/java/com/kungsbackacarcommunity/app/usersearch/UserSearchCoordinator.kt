package com.kungsbackacarcommunity.app.usersearch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the search field is currently showing. */
sealed interface UserSearchState {
    /** The field is empty — no hint, no rows, nothing. */
    data object Idle : UserSearchState

    /**
     * Something is typed, but not enough of it yet. A normal state, NOT an
     * error: the field renders a calm "keep typing" hint and no request is made.
     */
    data object TooShort : UserSearchState

    /**
     * A search is pending (still inside the debounce) or in flight.
     *
     * [previous] carries the rows from the last completed search so the list can
     * stay on screen, dimmed, while the next one runs. Blanking the results on
     * every keystroke makes a typeahead flicker badly and, worse, makes a row
     * jump out from under a finger already moving towards it.
     */
    data class Searching(val previous: List<MemberSearchResult>) : UserSearchState

    /** The search completed and matched nobody. */
    data object Empty : UserSearchState

    data class Results(val members: List<MemberSearchResult>) : UserSearchState

    data class Failed(val error: UserSearchError) : UserSearchState
}

/**
 * Drives the member typeahead: debounces keystrokes, cancels superseded
 * searches, and publishes a single [UserSearchState] for the screen to render.
 * Pure Kotlin (no Android, no Firebase) so every timing rule below is
 * unit-testable on virtual time with a fake repository.
 *
 * TWO GUARANTEES THIS CLASS EXISTS TO MAKE
 * ----------------------------------------
 * 1. NO REQUEST PER KEYSTROKE. Each change restarts a [debounceMillis] timer;
 *    only the pause at the end of a burst issues a call. Typing "gustav" makes
 *    one request, not six — which matters both for cost and for the backend's
 *    per-user rate limit.
 *
 * 2. RESULTS ALWAYS MATCH THE LATEST KEYSTROKES. A superseded search is
 *    cancelled at its suspension point (so it never even resumes), and its
 *    publication is ALSO guarded by an [activeQuery] equality check. The two are
 *    deliberately redundant: cancellation alone is a race (a call can complete
 *    between the last suspension point and the assignment), and the guard alone
 *    would leave doomed work running. Without both, a slow request for "gu" can
 *    land after a fast one for "gustav" and repaint the list with stale rows —
 *    the classic typeahead bug where the suggestion you tap is not the one you
 *    were looking at.
 */
class UserSearchCoordinator(
    private val repository: UserSearchRepository,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {
    private val stateFlow = MutableStateFlow<UserSearchState>(UserSearchState.Idle)
    val state: StateFlow<UserSearchState> = stateFlow.asStateFlow()

    /**
     * The NORMALIZED query the current state belongs to, or null when the field
     * is empty. Doubles as the staleness token: a search publishes only while
     * this still equals the query it was started for.
     */
    private var activeQuery: String? = null

    private var searchJob: Job? = null

    /**
     * Handles a change to the search field.
     *
     * Compared in NORMALIZED space, so edits that cannot change the result —
     * trailing whitespace, a case flip — do not restart the timer or refire the
     * callable. That check runs BEFORE anything is cancelled, so a no-op edit
     * can never kill a search that is already running for the same key and leave
     * the UI stuck on [UserSearchState.Searching].
     */
    fun onQueryChanged(rawQuery: String) {
        val normalized = UserSearchQuery.normalize(rawQuery)
        if (normalized == activeQuery) return

        activeQuery = normalized
        searchJob?.cancel()
        searchJob = null

        if (normalized.isEmpty()) {
            stateFlow.value = UserSearchState.Idle
            return
        }
        if (!UserSearchQuery.isSearchable(normalized)) {
            stateFlow.value = UserSearchState.TooShort
            return
        }

        stateFlow.value = UserSearchState.Searching(previous = currentResults())
        searchJob = scope.launch { runSearch(normalized) }
    }

    /** Clears the field's state (e.g. the screen is left, or the query is reset). */
    fun clear() {
        searchJob?.cancel()
        searchJob = null
        activeQuery = null
        stateFlow.value = UserSearchState.Idle
    }

    private suspend fun runSearch(normalizedQuery: String) {
        try {
            delay(debounceMillis)
            val outcome = repository.search(normalizedQuery)
            // Staleness guard — see the class KDoc. Cancellation normally makes
            // this unreachable, but a call that completes in the window between
            // its last suspension point and here would otherwise publish over a
            // newer query's state.
            if (activeQuery != normalizedQuery) return
            stateFlow.value =
                when (outcome) {
                    is UserSearchOutcome.Loaded ->
                        if (outcome.members.isEmpty()) {
                            UserSearchState.Empty
                        } else {
                            UserSearchState.Results(outcome.members)
                        }
                    // The backend applies the same minimum; if the two ever
                    // disagree, follow the backend and show the hint rather than
                    // an error the user cannot act on.
                    UserSearchOutcome.TooShort -> UserSearchState.TooShort
                    is UserSearchOutcome.Failed -> UserSearchState.Failed(outcome.error)
                }
        } catch (cancellation: CancellationException) {
            // A superseded search. Rethrow so the coroutine machinery sees a
            // normal cancellation, and publish NOTHING — the newer query already
            // owns the state.
            throw cancellation
        } catch (error: Exception) {
            if (activeQuery != normalizedQuery) return
            stateFlow.value = UserSearchState.Failed(UserSearchError.Generic)
        }
    }

    /** Rows currently on screen, so a re-search can keep showing them. */
    private fun currentResults(): List<MemberSearchResult> =
        when (val current = stateFlow.value) {
            is UserSearchState.Results -> current.members
            is UserSearchState.Searching -> current.previous
            else -> emptyList()
        }

    companion object {
        /**
         * Pause after the last keystroke before a search is issued.
         *
         * Long enough that a burst of typing collapses into one request, short
         * enough that the list still feels like it is reacting to the keyboard.
         * Around a quarter second is the usual sweet spot; below ~150 ms the
         * debounce stops collapsing anything for an average typist, and above
         * ~400 ms the field starts to feel laggy.
         */
        const val DEFAULT_DEBOUNCE_MILLIS = 275L
    }
}
