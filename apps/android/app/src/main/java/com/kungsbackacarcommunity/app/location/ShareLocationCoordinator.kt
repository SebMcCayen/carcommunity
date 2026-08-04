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

    // Idempotency keys for shares that are in flight or have failed, keyed by the
    // target uid AND the exact message body. A manual retry of a FAILED share (same
    // friend, same location text) reuses the key, so `dm-sendMessage` — which uses
    // clientId verbatim as the message doc id, first-write-wins — dedups a send that
    // landed server-side but whose ack the client never saw. Keying on the MESSAGE
    // (not just the uid) is essential: a DIFFERENT location to the same friend must
    // mint a fresh key, or first-write-wins would silently DROP it as a "duplicate".
    // Serialized by the send guard below (one share in flight at a time), so this
    // plain map needs no extra synchronization. Cleared once a send is confirmed.
    private val pendingClientIds = mutableMapOf<String, String>()

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
        // Atomic claim: two rapid taps can otherwise both read null before either
        // writes, firing two sends (with different clientIds, so clientId dedup
        // would NOT save us). compareAndSet makes the busy check-and-set one step;
        // a loser gets the ignored/busy path (false), never a spurious failure.
        if (!sendingFlow.compareAndSet(expect = null, update = friend.uid)) return false
        val text = LocationShare.messageText(location.name, location.point)
        // Key on uid + the exact message: a retry of the SAME share reuses its
        // clientId (idempotent); a different location mints a fresh one so it is
        // not dropped as a first-write-wins duplicate. UUID hex + dashes satisfy
        // the callable's clientId charset ([A-Za-z0-9_-]).
        val key = "${friend.uid}\n$text"
        val clientId = pendingClientIds.getOrPut(key) { java.util.UUID.randomUUID().toString() }
        return try {
            when (dmRepository.sendMessage(friend.uid, text, clientId)) {
                is DmSendResult.Sent -> {
                    // Confirmed: retire the key so a deliberate re-share of the same
                    // location later is a NEW message, not a deduped repeat.
                    pendingClientIds.remove(key)
                    true
                }
                // Keep the key so a manual retry is idempotent against a send that
                // may have landed server-side.
                is DmSendResult.Failed -> false
            }
        } finally {
            // Clear only our own marker (a no-op if something else already reset it).
            sendingFlow.compareAndSet(expect = friend.uid, update = null)
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
