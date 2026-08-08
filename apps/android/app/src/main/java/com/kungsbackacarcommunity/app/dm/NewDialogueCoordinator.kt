package com.kungsbackacarcommunity.app.dm

import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing state of the "start a new dialogue" friend picker. */
sealed interface NewDialogueState {
    data object Loading : NewDialogueState

    /**
     * The friends a new DM may be started with, already filtered to eligible rows
     * and name-ordered ([NewDialogue.targets]). An EMPTY list is a valid,
     * non-error state — the member simply has no friends yet — and the picker
     * renders its own "add a friend first" empty state for it.
     */
    data class Ready(val friends: List<FriendSummary>) : NewDialogueState

    /** The friends snapshot failed to load; the picker offers a retry. */
    data object Error : NewDialogueState
}

/**
 * Loads the member's friends for the DM inbox's "start a new dialogue" picker.
 * Unlike [com.kungsbackacarcommunity.app.events.EventShareCoordinator] there is
 * NO send step: picking a friend just opens (or re-opens) the DM thread with
 * them, which is pure navigation — ChatRoute derives the pairId and the first
 * sent message creates the document. Pure Kotlin (no Compose) so it is
 * unit-testable with a fake friends source.
 */
class NewDialogueCoordinator(
    private val friendsSource: DmFriendsSource,
) {
    /** Narrow seam over the friends repository (mirrors the share coordinators). */
    fun interface DmFriendsSource {
        suspend fun list(): FriendsResult
    }

    private val stateFlow = MutableStateFlow<NewDialogueState>(NewDialogueState.Loading)
    val state: StateFlow<NewDialogueState> = stateFlow.asStateFlow()

    /** Loads (or reloads, on retry) the eligible friends. */
    suspend fun load() {
        stateFlow.value = NewDialogueState.Loading
        stateFlow.value =
            when (val result = friendsSource.list()) {
                is FriendsResult.Loaded -> NewDialogueState.Ready(NewDialogue.targets(result.data))
                is FriendsResult.Failed -> NewDialogueState.Error
            }
    }

    companion object {
        /** Adapts a full [FriendsRepository] to the narrow source. */
        fun fromFriendsRepository(friends: FriendsRepository): NewDialogueCoordinator =
            NewDialogueCoordinator { friends.list() }
    }
}
