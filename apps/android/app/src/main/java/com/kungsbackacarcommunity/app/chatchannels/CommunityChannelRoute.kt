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
import kotlinx.coroutines.launch

/**
 * Community-channel integration route: subscribes the live newest-window
 * listener, drives [ChannelChatContent] through a [ChannelChatCoordinator], and
 * marks the channel read on open + whenever a new incoming message arrives while
 * it is open. Mirrors dm/ChatRoute.
 *
 * [onViewProfile] is threaded straight through to the sender headers (tap a
 * sender → their read-only member profile); null leaves them inert.
 */
@Composable
fun CommunityChannelRoute(
    repository: CommunityChatRepository,
    uid: String,
    modifier: Modifier = Modifier,
    onViewProfile: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val coordinator =
        remember(repository) {
            ChannelChatCoordinator(
                sender = { text -> repository.post(text) },
                pager = { before -> repository.loadOlder(before) },
                marker = { repository.markRead() },
            )
        }

    val messagesState by
        remember(repository) { repository.observeMessages() }
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
        sendStatus = sendStatus,
        canLoadOlder = pageStatus != ChannelPageStatus.End &&
            displayed.size >= CHANNEL_MESSAGES_PAGE_SIZE &&
            olderCursor != null,
        isLoadingOlder = pageStatus == ChannelPageStatus.Loading,
        onSend = { text -> scope.launch { coordinator.send(text) } },
        onLoadOlder = { scope.launch { coordinator.loadOlder(olderCursor) } },
        onResetError = { coordinator.resetSendError() },
        modifier = modifier,
        onViewProfile = onViewProfile,
    )
}
