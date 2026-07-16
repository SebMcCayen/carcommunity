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
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.diagnostics.ClientErrorReporter
import com.kungsbackacarcommunity.app.diagnostics.FirebaseClientErrorReporter
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
    errorReporter: ClientErrorReporter? = defaultClientErrorReporter(),
) {
    // A retry bumps [retryKey], re-subscribing the inbox listener (a transient
    // failure — offline, or a not-yet-active composite index — can then recover
    // without leaving the user on a dead-end error).
    var retryKey by remember(uid) { mutableStateOf(0) }
    val state by
        remember(repository, uid, retryKey) { repository.observeConversations(uid) }
            .collectAsState(initial = DmConversationsState.Loading)

    // Report a GENUINE load failure (never the empty state) to the admin Audit
    // Log + deduped GitHub-issue pipeline. Keyed so it fires ONCE per entry into
    // the Error state (and once per retry), not on every recomposition.
    val error = state as? DmConversationsState.Error
    LaunchedEffect(error != null, retryKey) {
        if (error != null) {
            errorReporter?.report(
                feature = FEATURE_CONVERSATION_LIST,
                message = "Conversation inbox listener failed to load",
                code = error.code,
            )
        }
    }

    ConversationListScreen(
        state = state,
        onOpenConversation = onOpenConversation,
        onRetry = { retryKey++ },
    )
}

/** Stable feature key for the Messages inbox (matches the backend fingerprint input). */
private const val FEATURE_CONVERSATION_LIST = "messages.conversationList"

/**
 * Builds the Firebase-backed [ClientErrorReporter] from the local context, or
 * null in a config-less build. Isolated as the route's default so callers don't
 * have to thread it through, and tests can inject a fake.
 */
@Composable
private fun defaultClientErrorReporter(): ClientErrorReporter? {
    val context = LocalContext.current
    return remember(context) { FirebaseClientErrorReporter.createIfAvailable(context) }
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
 *
 * [onViewProfile] opens [otherUid]'s read-only member profile from the thread
 * title; null (a config-less build with no profile repository) leaves it inert.
 */
@Composable
fun ChatRoute(
    repository: DmRepository,
    uid: String,
    otherUid: String,
    otherName: String?,
    onViewProfile: ((String) -> Unit)? = null,
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

    // Mark read on open, and again whenever a NEW INCOMING message lands while
    // the thread is open. A newest message that is the caller's own send carries
    // no unread to clear, so it must not trigger a needless markRead callable.
    LaunchedEffect(conversationId) { coordinator.markRead() }
    LaunchedEffect(liveMessages.lastOrNull()?.id) {
        coordinator.markReadIfIncoming(liveMessages.lastOrNull())
    }

    // Re-subscribe once the first send creates the conversation document (the
    // initial listen was denied for the not-yet-existing doc). Gated to AT MOST
    // ONCE per conversationId via [resubscribed]: a burst of quick sends (before
    // the first snapshot arrives, so liveMessages is still empty) must not
    // repeatedly tear down and recreate the Firestore listener.
    var resubscribed by remember(conversationId) { mutableStateOf(false) }
    val liveEmpty = rememberUpdatedState(liveMessages.isEmpty())
    LaunchedEffect(sentCount) {
        if (sentCount > 0 && liveEmpty.value && !resubscribed) {
            resubscribed = true
            threadKey++
        }
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
        // Guarded on a resolvable target: a blank otherUid would open a dead
        // profile route (dmPairId can be derived from a malformed conversation).
        onViewProfile =
            onViewProfile?.takeIf { otherUid.isNotBlank() }?.let { { it(otherUid) } },
    )
}
