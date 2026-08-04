package com.kungsbackacarcommunity.app.location

import com.kungsbackacarcommunity.app.dm.DmRepository
import com.kungsbackacarcommunity.app.dm.DmSendResult
import com.kungsbackacarcommunity.app.friends.FriendShareTargets
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing state of the friend picker behind a "share this location" flow. */
sealed interface ShareLocationState {
    data object Loading : ShareLocationState

    /**
     * The friends the location can be shared with, already filtered to eligible
     * rows and name-ordered ([FriendShareTargets]). An EMPTY list is a valid,
     * non-error state — the member simply has no friends yet — and the picker
     * renders its own "add friends first" empty state for it.
     */
    data class Ready(val friends: List<FriendSummary>) : ShareLocationState

    /** The friends snapshot failed to load; the picker offers a retry. */
    data object Error : ShareLocationState
}

/**
 * Orchestrates the shared "share a location with a friend" flow: load the
 * member's friends, then deliver the picked location to the chosen friend as a
 * direct message. Pure Kotlin (no Compose) so it is unit-testable with fake
 * repositories.
 *
 * Delivery reuses the EXISTING DM send path verbatim — there is no new backend,
 * callable, collection, rules change, or message type. The location travels as an
 * ordinary message whose body carries a `geo:` token ([LocationShare.messageText])
 * that the recipient's chat renders as a tappable "show on map" chip. Sending is
 * already gated to established friends server-side, and the picker only ever
 * offers friends, so the send can never be refused for "not friends".
 */
class ShareLocationCoordinator(
    private val friendsRepository: DmFriendsSource,
    private val dmRepository: DmRepository,
) {
    /**
     * Narrow seam over the friends repository so this coordinator depends only on
     * "list the friends", not the whole `FriendsRepository` surface — keeps the
     * unit-test fake tiny. The production adapter is [fromFriendsRepository].
     */
    fun interface DmFriendsSource {
        suspend fun list(): FriendsResult
    }

    private val stateFlow = MutableStateFlow<ShareLocationState>(ShareLocationState.Loading)
    val state: StateFlow<ShareLocationState> = stateFlow.asStateFlow()

    // The uid a share is currently in flight to, so the picker can show a spinner
    // on that one row and ignore repeat taps. Null when nothing is sending.
    private val sendingFlow = MutableStateFlow<String?>(null)
    val sending: StateFlow<String?> = sendingFlow.asStateFlow()

    /** Loads (or reloads, on retry) the eligible friends. */
    suspend fun load() {
        stateFlow.value = ShareLocationState.Loading
        stateFlow.value =
            when (val result = friendsRepository.list()) {
                is FriendsResult.Loaded -> ShareLocationState.Ready(FriendShareTargets.from(result.data))
                is FriendsResult.Failed -> ShareLocationState.Error
            }
    }

    /**
     * Delivers [location] to [friend] as a DM. Returns true on a confirmed send.
     * A second tap while a send is already in flight is ignored (returns false).
     */
    suspend fun share(friend: FriendSummary, location: ShareableLocation): Boolean {
        if (sendingFlow.value != null) return false
        sendingFlow.value = friend.uid
        return try {
            val text = LocationShare.messageText(location.name, location.point)
            when (dmRepository.sendMessage(friend.uid, text)) {
                is DmSendResult.Sent -> true
                is DmSendResult.Failed -> false
            }
        } finally {
            sendingFlow.value = null
        }
    }

    companion object {
        /** Adapts a full [com.kungsbackacarcommunity.app.friends.FriendsRepository] to the narrow source. */
        fun fromFriendsRepository(
            friends: com.kungsbackacarcommunity.app.friends.FriendsRepository,
            dm: DmRepository,
        ): ShareLocationCoordinator = ShareLocationCoordinator({ friends.list() }, dm)
    }
}
