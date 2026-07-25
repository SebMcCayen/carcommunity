package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.blocking.BlockingCoordinator
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Convoy-channel integration route: subscribes the live newest-window listener
 * for [convoyId], drives [ChannelChatContent] through a [ChannelChatCoordinator]
 * (send + older-page; convoy channels carry no unread marker, so no mark-read).
 * Mirrors [CommunityChannelRoute].
 *
 * Deliberately NO @mentions, unlike the community channel: convoyChat-post
 * accepts none (a convoy already notifies all of its <= 50 accepted members on
 * every message, so a mention notice would duplicate one they're getting anyway —
 * and under a category they may have silenced separately), and every convoy
 * message stores `mentionedUids: []`. Passing no [MentionCandidate]s leaves the
 * picker off, and the empty stored set means nothing highlights.
 *
 * [onViewProfile] is threaded straight through to the sender headers (tap a
 * sender → their read-only member profile); null leaves them inert.
 *
 * [blockingRepository] wires the long-press action sheet's block action; null
 * (config-less build) leaves the sheet's block row off. As on the community
 * channel the convoy roster's messages are not block-filtered — a convoy is a
 * roster you both joined, so hiding a member's messages mid-drive would be a
 * safety hazard; the block still applies to DMs, live location and interaction.
 */
@Composable
fun ConvoyChannelRoute(
    repository: ConvoyChatRepository,
    uid: String,
    convoyId: String,
    modifier: Modifier = Modifier,
    onViewProfile: ((String) -> Unit)? = null,
    blockingRepository: BlockingRepository? = null,
) {
    val scope = rememberCoroutineScope()
    val blockingCoordinator =
        remember(blockingRepository) { blockingRepository?.let { BlockingCoordinator(it) } }
    // Clear a stale Done/Failed banner when re-entering, or on a convoy switch.
    LaunchedEffect(convoyId, blockingCoordinator) { blockingCoordinator?.reset() }
    val blockStatus by
        (blockingCoordinator?.actionStatus ?: flowOf(BlockActionStatus.Idle))
            .collectAsState(initial = BlockActionStatus.Idle)
    val coordinator =
        remember(repository, convoyId, uid) {
            ChannelChatCoordinator(
                // convoyChat-post takes no mentions; the uid list is always empty
                // here and is dropped rather than forwarded. The clientId makes the
                // optimistic send idempotent + reconcilable (backend uses it as the
                // message doc id).
                sender = { text, _, clientId -> repository.post(convoyId, text, clientId) },
                pager = { before -> repository.loadOlder(convoyId, before) },
                selfUid = uid,
                marker = null,
            )
        }

    val messagesState by
        remember(repository, convoyId) { repository.observeMessages(convoyId) }
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
    // The next older-page cursor: the earliest loaded message's ISO createdAt, or
    // null when it lacks createdAt. A null cursor makes older-paging a no-op, so
    // gate the "Load earlier" affordance on it and reuse the same cursor to page.
    val olderCursor = remember(displayed) { ChannelThread.oldestCursor(displayed) }

    ChannelChatContent(
        messages = displayed,
        currentUid = uid,
        loading = messagesState is ChannelMessagesState.Loading,
        emptyText = stringResource(R.string.channel_emptyConvoy),
        canLoadOlder = pageStatus != ChannelPageStatus.End &&
            displayed.size >= CHANNEL_MESSAGES_PAGE_SIZE &&
            olderCursor != null,
        isLoadingOlder = pageStatus == ChannelPageStatus.Loading,
        onSend = { text, _ -> scope.launch { coordinator.send(text) } },
        onRetry = { message ->
            message.clientId?.let { clientId -> scope.launch { coordinator.retry(clientId) } }
        },
        onLoadOlder = { scope.launch { coordinator.loadOlder(olderCursor) } },
        modifier = modifier,
        onViewProfile = onViewProfile,
        surface = ChatSurface.ConvoyChannel,
        onBlock =
            blockingCoordinator?.let { c ->
                { targetUid ->
                    // No pre-block reset — see CommunityChannelRoute.
                    scope.launch { c.block(targetUid) }
                }
            },
        blockStatus = blockStatus,
        onBlockDismiss = { blockingCoordinator?.reset() },
    )
}
