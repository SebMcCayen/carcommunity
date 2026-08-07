package com.kungsbackacarcommunity.app.events

import com.kungsbackacarcommunity.app.dm.DmRepository
import com.kungsbackacarcommunity.app.dm.DmSendResult
import com.kungsbackacarcommunity.app.friends.FriendShareTargets
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing state of the friend picker behind a "share this event" flow. */
sealed interface EventShareState {
    data object Loading : EventShareState

    /**
     * The friends the event can be shared with, already filtered to eligible rows
     * and name-ordered ([FriendShareTargets]). An EMPTY list is a valid, non-error
     * state — the member simply has no friends yet — and the picker renders its own
     * "add friends first" empty state for it.
     */
    data class Ready(val friends: List<FriendSummary>) : EventShareState

    /** The friends snapshot failed to load; the picker offers a retry. */
    data object Error : EventShareState
}

/**
 * Orchestrates the "share an event with a friend" flow: load the member's friends,
 * then deliver the picked event to the chosen friend as a direct message. Pure
 * Kotlin (no Compose) so it is unit-testable with fake repositories.
 *
 * A carbon copy of the location-share orchestration
 * ([com.kungsbackacarcommunity.app.location.ShareLocationCoordinator]) — the ONLY
 * difference is the message body ([EventShare.messageText] carries a `kccevent:`
 * token instead of a `geo:` one). Delivery reuses the EXISTING `dm-sendMessage`
 * send path verbatim: there is no new backend, callable, collection, rules change,
 * or message type. The recipient's chat detects the token and renders a tappable
 * "Open event" chip that opens THAT event's detail page.
 */
class EventShareCoordinator(
    private val friendsSource: DmFriendsSource,
    private val dmRepository: DmRepository,
) {
    /** Narrow seam over the friends repository (mirrors ShareLocationCoordinator). */
    fun interface DmFriendsSource {
        suspend fun list(): FriendsResult
    }

    private val stateFlow = MutableStateFlow<EventShareState>(EventShareState.Loading)
    val state: StateFlow<EventShareState> = stateFlow.asStateFlow()

    private val sendingFlow = MutableStateFlow<String?>(null)
    val sending: StateFlow<String?> = sendingFlow.asStateFlow()

    // Idempotency keys for shares in flight or failed, keyed by target uid AND the
    // exact message body — so a manual retry of a FAILED share reuses its clientId
    // (first-write-wins dedup), while a DIFFERENT event to the same friend mints a
    // fresh key. Serialized by the send guard below, so this plain map needs no
    // extra synchronization. Mirrors ShareLocationCoordinator exactly.
    private val pendingClientIds = mutableMapOf<String, String>()

    /** Loads (or reloads, on retry) the eligible friends. */
    suspend fun load() {
        stateFlow.value = EventShareState.Loading
        stateFlow.value =
            when (val result = friendsSource.list()) {
                is FriendsResult.Loaded -> EventShareState.Ready(FriendShareTargets.from(result.data))
                is FriendsResult.Failed -> EventShareState.Error
            }
    }

    /**
     * Delivers the event ([eventId] + [title]) to [friend] as a DM. Returns true on
     * a confirmed send. A second tap while a send is already in flight is ignored
     * (returns false).
     */
    suspend fun share(friend: FriendSummary, eventId: String, title: String?): Boolean {
        // Atomic claim: two rapid taps can otherwise both read null before either
        // writes. compareAndSet makes the busy check-and-set one step; a loser gets
        // the ignored/busy path (false), never a spurious failure.
        if (!sendingFlow.compareAndSet(expect = null, update = friend.uid)) return false
        val text = EventShare.messageText(title, eventId)
        val key = "${friend.uid}\n$text"
        val clientId = pendingClientIds.getOrPut(key) { java.util.UUID.randomUUID().toString() }
        return try {
            when (dmRepository.sendMessage(friend.uid, text, clientId)) {
                is DmSendResult.Sent -> {
                    pendingClientIds.remove(key)
                    true
                }
                is DmSendResult.Failed -> false
            }
        } finally {
            sendingFlow.compareAndSet(expect = friend.uid, update = null)
        }
    }

    companion object {
        /** Adapts a full [com.kungsbackacarcommunity.app.friends.FriendsRepository] to the narrow source. */
        fun fromFriendsRepository(
            friends: com.kungsbackacarcommunity.app.friends.FriendsRepository,
            dm: DmRepository,
        ): EventShareCoordinator = EventShareCoordinator({ friends.list() }, dm)
    }
}
