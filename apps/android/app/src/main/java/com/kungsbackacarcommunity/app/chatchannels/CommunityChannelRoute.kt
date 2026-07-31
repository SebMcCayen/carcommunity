package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.blocking.BlockingCoordinator
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import com.kungsbackacarcommunity.app.diagnostics.NoopCrashTelemetry
import com.kungsbackacarcommunity.app.diagnostics.rememberCrashTelemetry
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsResult
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Community-channel integration route: subscribes the live newest-window
 * listener, drives [ChannelChatContent] through a [ChannelChatCoordinator], and
 * marks the channel read on open + whenever a new incoming message arrives while
 * it is open. Mirrors dm/ChatRoute.
 *
 * It also sources the @-picker's roster. There is NO community-wide member
 * listing to query — no repository exposes one, and adding a backend query is
 * outside the Android lane — so the roster is assembled from the two rosters
 * already in hand, at the cost of no new query:
 *  - the caller's friends, via one `friend-list` call ([friendsRepository]; null
 *    in a config-less build, which narrows the picker to senders alone);
 *  - the authors of the loaded messages, denormalized onto each message
 *    (senderUid + senderDisplayName) — i.e. whoever is actually talking in the
 *    conversation being replied to, which is the realistic reason to @ someone
 *    in an app-wide channel.
 *
 * The name map used for HIGHLIGHTING is deliberately a superset of that roster:
 * it keeps the caller's own name (the picker excludes it, since the server drops
 * self-mentions, but being mentioned BY someone else must still highlight),
 * resolved from the caller's own messages in the loaded window. A mentioned uid
 * outside all of these renders unhighlighted; they were still notified.
 *
 * [onViewProfile] is threaded straight through to the sender headers (tap a
 * sender → their read-only member profile); null leaves them inert.
 *
 * [blockingRepository] wires the long-press action sheet's block action; null
 * (config-less build) leaves the sheet's block row off. Blocking from here DOES
 * now remove the blocked member's messages from this list, and mutually — they
 * stop seeing the blocker's messages too. Nothing is filtered at THIS layer:
 * older pages are filtered by `communityChat-list` server-side and the live
 * window inside the repository, against the backend's `blockVisibility` mirror
 * (see [com.kungsbackacarcommunity.app.blocking.BlockVisibility]). The list
 * therefore updates a moment AFTER the block callable returns, when the mirror
 * catches up — not synchronously with the tap.
 */
@Composable
fun CommunityChannelRoute(
    repository: CommunityChatRepository,
    uid: String,
    friendsRepository: FriendsRepository? = null,
    modifier: Modifier = Modifier,
    onViewProfile: ((String) -> Unit)? = null,
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)? = null,
    blockingRepository: BlockingRepository? = null,
) {
    val scope = rememberCoroutineScope()
    val blockingCoordinator =
        remember(blockingRepository) { blockingRepository?.let { BlockingCoordinator(it) } }
    // Clear a stale Done/Failed banner when re-entering the channel.
    LaunchedEffect(blockingCoordinator) { blockingCoordinator?.reset() }
    val blockStatus by
        (blockingCoordinator?.actionStatus ?: flowOf(BlockActionStatus.Idle))
            .collectAsState(initial = BlockActionStatus.Idle)
    val crashTelemetry = rememberCrashTelemetry()
    val coordinator =
        remember(repository, uid, crashTelemetry) {
            ChannelChatCoordinator(
                // The clientId makes the optimistic send idempotent + reconcilable
                // (backend uses it as the message doc id).
                sender = { text, mentionedUids, clientId ->
                    repository.post(text, mentionedUids, clientId)
                },
                pager = { before -> repository.loadOlder(before) },
                selfUid = uid,
                marker = { repository.markRead() },
                crashTelemetry = crashTelemetry ?: NoopCrashTelemetry,
            )
        }

    val messagesState by
        remember(repository) { repository.observeMessages() }
            .collectAsState(initial = ChannelMessagesState.Loading)
    val liveMessages = (messagesState as? ChannelMessagesState.Loaded)?.messages ?: emptyList()

    val older by coordinator.olderMessages.collectAsState()
    val pending by coordinator.pendingMessages.collectAsState()
    // Server messages merged with the caller's optimistic bubbles; a bubble whose
    // delivered doc has arrived (matched by clientId == doc id) is dropped, so an
    // optimistic send and its snapshot render as exactly one message.
    val displayed =
        remember(older, liveMessages, pending) {
            ChannelThread.mergeWithPending(older, liveMessages, pending)
        }
    // Reconcile the optimistic bubbles against every live snapshot: once the real
    // document lands, drop the matching pending bubble.
    LaunchedEffect(liveMessages) { coordinator.onLiveMessages(liveMessages) }
    val pageStatus by coordinator.pageStatus.collectAsState()
    val droppedMentions by coordinator.droppedMentionCount.collectAsState()
    // The next older-page cursor: the earliest loaded message's ISO createdAt, or
    // null when it lacks createdAt. A null cursor makes older-paging a no-op, so
    // gate the "Load earlier" affordance on it and reuse the same cursor to page.
    val olderCursor = remember(displayed) { ChannelThread.oldestCursor(displayed) }

    // One `friend-list` fetch per open. A failure is silent: no friends merely
    // narrows the picker to the members talking in the channel, and a chat must
    // not surface a friends-graph error the reader can do nothing about.
    var friends by remember(friendsRepository) { mutableStateOf(emptyList<MentionCandidate>()) }
    LaunchedEffect(friendsRepository) {
        val result = friendsRepository?.list() ?: return@LaunchedEffect
        if (result is FriendsResult.Loaded) {
            friends =
                result.data.friends.mapNotNull { friend ->
                    val name =
                        friend.displayName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MentionCandidate(friend.uid, name, friend.avatarPath)
                }
        }
    }

    val senders = remember(displayed) { MentionCandidates.sendersOf(displayed) }
    val mentionCandidates =
        remember(friends, senders, uid) {
            MentionCandidates.from(friends = friends, messageSenders = senders, selfUid = uid)
        }
    val mentionDisplayNames =
        remember(friends, senders) { MentionCandidates.displayNames(friends + senders) }

    // Mark read on open, and again when a new INCOMING message lands while open.
    LaunchedEffect(Unit) { coordinator.markRead() }
    LaunchedEffect(liveMessages.lastOrNull()?.id) {
        val newest = liveMessages.lastOrNull()
        if (newest != null && newest.senderUid != uid) coordinator.markRead()
    }

    ChannelChatContent(
        messages = displayed,
        currentUid = uid,
        loading = messagesState is ChannelMessagesState.Loading,
        emptyText = stringResource(R.string.channel_emptyCommunity),
        canLoadOlder = pageStatus != ChannelPageStatus.End &&
            displayed.size >= CHANNEL_MESSAGES_PAGE_SIZE &&
            olderCursor != null,
        isLoadingOlder = pageStatus == ChannelPageStatus.Loading,
        onSend = { text, mentionedUids -> scope.launch { coordinator.send(text, mentionedUids) } },
        onRetry = { message ->
            message.clientId?.let { clientId -> scope.launch { coordinator.retry(clientId) } }
        },
        onLoadOlder = { scope.launch { coordinator.loadOlder(olderCursor) } },
        modifier = modifier,
        mentionCandidates = mentionCandidates,
        mentionDisplayNames = mentionDisplayNames,
        droppedMentionCount = droppedMentions,
        onDismissDroppedMentions = { coordinator.dismissDroppedMentions() },
        onViewProfile = onViewProfile,
        onShowLocationOnMap = onShowLocationOnMap,
        surface = ChatSurface.CommunityChannel,
        onBlock =
            blockingCoordinator?.let { c ->
                { targetUid ->
                    // No pre-block reset: BlockingCoordinator.block guards duplicate
                    // taps via its in-flight (Working) state, which a reset to Idle
                    // would defeat. Mirrors EventChatRoute.
                    scope.launch { c.block(targetUid) }
                }
            },
        blockStatus = blockStatus,
        onBlockDismiss = { blockingCoordinator?.reset() },
    )
}
