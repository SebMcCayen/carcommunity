package com.kungsbackacarcommunity.app.dm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/**
 * Inbox integration route: subscribes the live conversation listener and drives
 * [ConversationListScreen]. Tapping a row opens the thread via [onOpenConversation].
 */
@Composable
fun ConversationListRoute(
    repository: DmRepository,
    uid: String,
    onOpenConversation: (DmConversation) -> Unit,
    onBack: () -> Unit,
) {
    val state by
        remember(repository, uid) { repository.observeConversations(uid) }
            .collectAsState(initial = DmConversationsState.Loading)

    ConversationListScreen(state = state, onOpenConversation = onOpenConversation)
}

/**
 * Thread integration route for the conversation with [otherUid]. The
 * conversation id is derived locally ([dmPairId]) — matching the backend — so a
 * thread opens without a lookup, and `dm-sendMessage` creates the document on
 * the first message.
 *
 * The displayed thread is the live newest-window ([DmRepository.observeThread])
 * merged with any accumulated older pages held by the coordinator. Mark-read
 * fires on open and whenever a new message arrives. Because the messages read
 * rule denies a listen on a not-yet-created conversation, [threadKey] is bumped
 * the first time a send creates the document, re-subscribing the (previously
 * empty) listener so the new thread streams in.
 */
@Composable
fun ChatRoute(
    repository: DmRepository,
    uid: String,
    otherUid: String,
    otherName: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val conversationId = remember(uid, otherUid) { dmPairId(uid, otherUid) }
    val coordinator =
        remember(repository, uid, otherUid, conversationId) {
            DmThreadCoordinator(repository, otherUid, conversationId)
        }

    var threadKey by remember(conversationId) { mutableStateOf(0) }
    val threadState by
        remember(repository, conversationId, threadKey) { repository.observeThread(conversationId) }
            .collectAsState(initial = DmThreadState.Loading)
    val liveMessages = (threadState as? DmThreadState.Loaded)?.messages ?: emptyList()

    val older by coordinator.olderMessages.collectAsState()
    val displayed = remember(older, liveMessages) { DmThread.merge(older, liveMessages) }
    val sendStatus by coordinator.sendStatus.collectAsState()
    val pageStatus by coordinator.pageStatus.collectAsState()
    val sentCount by coordinator.sentCount.collectAsState()

    // Mark read on open and whenever a new message lands while the thread is open.
    LaunchedEffect(conversationId) { coordinator.markRead() }
    LaunchedEffect(liveMessages.lastOrNull()?.id) {
        if (liveMessages.isNotEmpty()) coordinator.markRead()
    }

    // Re-subscribe once the first send creates the conversation document (the
    // initial listen was denied for the not-yet-existing doc).
    val liveEmpty = rememberUpdatedState(liveMessages.isEmpty())
    LaunchedEffect(sentCount) {
        if (sentCount > 0 && liveEmpty.value) threadKey++
    }

    ChatScreen(
        otherName = otherName,
        messages = displayed,
        currentUid = uid,
        threadLoading = threadState is DmThreadState.Loading,
        sendStatus = sendStatus,
        canLoadOlder = pageStatus != DmPageStatus.End && displayed.size >= DM_MESSAGES_PAGE_SIZE,
        isLoadingOlder = pageStatus == DmPageStatus.Loading,
        onSend = { text -> scope.launch { coordinator.send(text) } },
        onLoadOlder = { scope.launch { coordinator.loadOlder(DmThread.oldestCursor(displayed)) } },
        onResetError = { coordinator.resetSendError() },
    )
}
