package com.kungsbackacarcommunity.app.dm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.blocking.BlockingCoordinator
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.diagnostics.ClientErrorReporter
import com.kungsbackacarcommunity.app.diagnostics.NoopCrashTelemetry
import com.kungsbackacarcommunity.app.diagnostics.rememberClientErrorReporter
import com.kungsbackacarcommunity.app.diagnostics.rememberCrashTelemetry
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import kotlinx.coroutines.flow.flowOf
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
    errorReporter: ClientErrorReporter? = rememberClientErrorReporter(),
    // False inside the chat hub, whose "Friends" TAB already names this section;
    // true for the standalone route, where the header is the only thing that
    // does. See [ConversationListScreen].
    showTitle: Boolean = true,
    // When BOTH are wired, a "start a new dialogue" FAB is shown that opens a
    // friend picker; picking a friend opens (or re-opens) the DM thread with them
    // via [onOpenDm]. A config-less build — or a host that has no way to open a
    // thread — passes null for either and gets the plain inbox with no dead
    // control. [onOpenDm] receives the resolved peer uid + best-known display name.
    friendsRepository: FriendsRepository? = null,
    onOpenDm: ((uid: String, name: String?) -> Unit)? = null,
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

    // The inbox rows currently loaded, used to resolve whether a picked friend
    // re-opens an existing conversation or starts a new one (pure:
    // [NewDialogue.openTargetFor]). Empty while loading/errored — a pick then
    // simply opens a fresh thread, which is correct.
    val conversations = (state as? DmConversationsState.Loaded)?.conversations.orEmpty()
    var showPicker by remember { mutableStateOf(false) }
    val canStartNew = friendsRepository != null && onOpenDm != null

    Box(modifier = Modifier.fillMaxSize()) {
        ConversationListScreen(
            state = state,
            onOpenConversation = onOpenConversation,
            onRetry = { retryKey++ },
            showTitle = showTitle,
            reserveBottomActionSpace = canStartNew,
        )

        if (canStartNew) {
            // Material "compose new message" convention: a bottom-end FAB. This
            // section has no top app bar of its own to hang an action on (inside
            // the chat hub the tab row sits above it; the standalone route uses the
            // shared Aero chrome), so the FAB is both the conventional and the
            // best-fitting placement here.
            FloatingActionButton(
                onClick = { showPicker = true },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(KccSpacing.s4)
                        .testTag(NEW_DIALOGUE_FAB_TEST_TAG),
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.dm_newDialogue_action),
                )
            }

            if (showPicker) {
                NewDialogueSheet(
                    friendsRepository = friendsRepository,
                    onPick = { friend ->
                        showPicker = false
                        val target = NewDialogue.openTargetFor(friend, conversations)
                        // A blank uid would open a dead thread; the picker already
                        // drops blank-uid friends, this is belt-and-braces.
                        if (target.uid.isNotBlank()) {
                            onOpenDm(target.uid, target.displayName)
                        }
                    },
                    onDismiss = { showPicker = false },
                )
            }
        }
    }
}

/** Stable feature key for the Messages inbox (matches the backend fingerprint input). */
private const val FEATURE_CONVERSATION_LIST = "messages.conversationList"

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
 *
 * [blockingRepository] wires the long-press action sheet's block action on the
 * other member's messages; null (config-less build) leaves the sheet's block row
 * off. Blocking now takes the WHOLE thread out of view for both parties: the
 * conversation leaves both inboxes and firebase/firestore.rules denies the
 * messages subcollection outright, which this screen's existing
 * PERMISSION_DENIED branch already renders as an empty thread. Nothing is
 * filtered at this layer — a half-thread stripped of one side would read as a
 * monologue, and sending was already refused for a blocked pair
 * (`dm-sendMessage` returns a neutral failed-precondition), so the thread would
 * be a dead end anyway. Unblocking restores it whole.
 */
@Composable
fun ChatRoute(
    repository: DmRepository,
    uid: String,
    otherUid: String,
    otherName: String?,
    onViewProfile: ((String) -> Unit)? = null,
    blockingRepository: BlockingRepository? = null,
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)? = null,
    // Opens an event by id from a shared "Open event" chip in a message; null
    // leaves such tokens as plain text (no event navigation wired).
    onOpenEvent: ((String) -> Unit)? = null,
    // The `chatReplies` flag, threaded down from the hub. Gates the inline-reply
    // entry point; off, the thread behaves exactly as before.
    chatRepliesEnabled: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val conversationId = remember(uid, otherUid) { dmPairId(uid, otherUid) }
    val blockingCoordinator =
        remember(blockingRepository) { blockingRepository?.let { BlockingCoordinator(it) } }
    // Clear a stale Done/Failed banner when re-entering, or on a thread switch.
    LaunchedEffect(conversationId, blockingCoordinator) { blockingCoordinator?.reset() }
    val blockStatus by
        (blockingCoordinator?.actionStatus ?: flowOf(BlockActionStatus.Idle))
            .collectAsState(initial = BlockActionStatus.Idle)
    val crashTelemetry = rememberCrashTelemetry()
    val coordinator =
        remember(repository, uid, otherUid, conversationId, crashTelemetry) {
            DmThreadCoordinator(
                repository,
                selfUid = uid,
                otherUid = otherUid,
                conversationId = conversationId,
                crashTelemetry = crashTelemetry ?: NoopCrashTelemetry,
            )
        }

    var threadKey by remember(conversationId) { mutableStateOf(0) }
    val threadState by
        remember(repository, conversationId, threadKey) { repository.observeThread(conversationId) }
            .collectAsState(initial = DmThreadState.Loading)
    val liveMessages = (threadState as? DmThreadState.Loaded)?.messages ?: emptyList()

    val older by coordinator.olderMessages.collectAsState()
    val pending by coordinator.pendingMessages.collectAsState()
    // Server messages merged with the caller's optimistic bubbles; a bubble whose
    // delivered doc has arrived (matched by clientId == doc id) is dropped, so an
    // optimistic send and its snapshot render as exactly one message.
    val displayed =
        remember(older, liveMessages, pending) {
            DmThread.mergeWithPending(older, liveMessages, pending)
        }
    val pageStatus by coordinator.pageStatus.collectAsState()
    val sentCount by coordinator.sentCount.collectAsState()

    // Reconcile the optimistic bubbles against every live snapshot: once the real
    // document lands, drop the matching pending bubble.
    LaunchedEffect(liveMessages) { coordinator.onLiveMessages(liveMessages) }

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
        canLoadOlder = pageStatus != DmPageStatus.End && displayed.size >= DM_MESSAGES_PAGE_SIZE,
        isLoadingOlder = pageStatus == DmPageStatus.Loading,
        onSend = { text, replyTo -> scope.launch { coordinator.send(text, replyTo) } },
        onRetry = { message ->
            message.clientId?.let { clientId -> scope.launch { coordinator.retry(clientId) } }
        },
        onLoadOlder = { scope.launch { coordinator.loadOlder(DmThread.oldestCursor(displayed)) } },
        chatRepliesEnabled = chatRepliesEnabled,
        // Guarded on a resolvable target: a blank otherUid would open a dead
        // profile route (dmPairId can be derived from a malformed conversation).
        onViewProfile =
            onViewProfile?.takeIf { otherUid.isNotBlank() }?.let { { it(otherUid) } },
        otherUid = otherUid,
        onBlock =
            blockingCoordinator?.let { c ->
                { targetUid ->
                    // No pre-block reset — BlockingCoordinator.block's in-flight
                    // guard would be defeated by it. Mirrors EventChatRoute.
                    scope.launch { c.block(targetUid) }
                }
            },
        blockStatus = blockStatus,
        onBlockDismiss = { blockingCoordinator?.reset() },
        onShowLocationOnMap = onShowLocationOnMap,
        onOpenEvent = onOpenEvent,
    )
}
