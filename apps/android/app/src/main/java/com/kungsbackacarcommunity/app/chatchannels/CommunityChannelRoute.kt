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
 */
@Composable
fun CommunityChannelRoute(
    repository: CommunityChatRepository,
    uid: String,
    modifier: Modifier = Modifier,
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
            displayed.size >= CHANNEL_MESSAGES_PAGE_SIZE,
        isLoadingOlder = pageStatus == ChannelPageStatus.Loading,
        onSend = { text -> scope.launch { coordinator.send(text) } },
        onLoadOlder = { scope.launch { coordinator.loadOlder(ChannelThread.oldestCursor(displayed)) } },
        onResetError = { coordinator.resetSendError() },
        modifier = modifier,
    )
}
