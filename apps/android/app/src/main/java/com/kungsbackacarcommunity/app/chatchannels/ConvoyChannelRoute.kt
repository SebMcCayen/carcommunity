package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
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
 */
@Composable
fun ConvoyChannelRoute(
    repository: ConvoyChatRepository,
    uid: String,
    convoyId: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val coordinator =
        remember(repository, convoyId) {
            ChannelChatCoordinator(
                // convoyChat-post takes no mentions; the uid list is always empty
                // here and is dropped rather than forwarded.
                sender = { text, _ -> repository.post(convoyId, text) },
                pager = { before -> repository.loadOlder(convoyId, before) },
                marker = null,
            )
        }

    val messagesState by
        remember(repository, convoyId) { repository.observeMessages(convoyId) }
            .collectAsState(initial = ChannelMessagesState.Loading)
    val liveMessages = (messagesState as? ChannelMessagesState.Loaded)?.messages ?: emptyList()

    val older by coordinator.olderMessages.collectAsState()
    val displayed = remember(older, liveMessages) { ChannelThread.merge(older, liveMessages) }
    val sendStatus by coordinator.sendStatus.collectAsState()
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
        sendStatus = sendStatus,
        canLoadOlder = pageStatus != ChannelPageStatus.End &&
            displayed.size >= CHANNEL_MESSAGES_PAGE_SIZE &&
            olderCursor != null,
        isLoadingOlder = pageStatus == ChannelPageStatus.Loading,
        onSend = { text, _ -> scope.launch { coordinator.send(text) } },
        onLoadOlder = { scope.launch { coordinator.loadOlder(olderCursor) } },
        onResetError = { coordinator.resetSendError() },
        modifier = modifier,
    )
}
