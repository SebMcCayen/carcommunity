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
                sender = { text -> repository.post(convoyId, text) },
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

    ChannelChatContent(
        messages = displayed,
        currentUid = uid,
        loading = messagesState is ChannelMessagesState.Loading,
        emptyText = stringResource(R.string.channel_emptyConvoy),
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
