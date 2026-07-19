package com.kungsbackacarcommunity.app.dm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.chattime.ChatDateContext
import com.kungsbackacarcommunity.app.chattime.ChatTimeline
import com.kungsbackacarcommunity.app.chattime.ChatTimelineItem
import com.kungsbackacarcommunity.app.chattime.DaySeparatorRow
import com.kungsbackacarcommunity.app.chattime.MessageTimeText
import com.kungsbackacarcommunity.app.chattime.rememberChatDateContext
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.moderation.BlockConfirmDialog
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import com.kungsbackacarcommunity.app.moderation.MessageActionsSheet
import com.kungsbackacarcommunity.app.moderation.MessageModeration
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * A 1:1 DM thread: own vs other message bubbles (chronological, newest at the
 * bottom), an optional "load earlier" affordance at the top, and a text input +
 * send. Stateless apart from the message draft; the draft clears only once a
 * send actually succeeds ([sendStatus] returns to Idle after Sending).
 *
 * Rendered on the shared [AeroPage] chrome (title = the other member's name),
 * with the message list taking the remaining height so the input pins to the
 * bottom.
 *
 * @param onViewProfile opens the other member's read-only profile. A 1:1 thread
 *   shows no per-message sender header (every incoming message is from the same
 *   member), so the TITLE — which already names them — is the tap target, the
 *   conventional "tap the thread header for who you're talking to". Null (the
 *   default) leaves the title inert. The caller's own messages are never a tap
 *   target here, matching the group channels.
 * @param otherUid the thread's other participant — the block target behind an
 *   incoming message's long-press. Blank (a malformed thread) leaves the
 *   moderation sheet unwired rather than targeting nobody.
 * @param onBlock blocks [otherUid]. Null (the default) means blocking is unwired
 *   (config-less build), and the sheet then omits the block row.
 */
@Composable
fun ChatScreen(
    otherName: String?,
    messages: List<DmMessage>,
    currentUid: String,
    threadLoading: Boolean,
    sendStatus: DmSendStatus,
    canLoadOlder: Boolean,
    isLoadingOlder: Boolean,
    onSend: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onResetError: () -> Unit,
    modifier: Modifier = Modifier,
    onViewProfile: (() -> Unit)? = null,
    otherUid: String = "",
    onBlock: ((String) -> Unit)? = null,
    blockStatus: BlockActionStatus = BlockActionStatus.Idle,
    onBlockDismiss: () -> Unit = {},
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var awaitingSend by rememberSaveable { mutableStateOf(false) }
    // Held by message ID (Saveable, so the sheet survives rotation) and resolved
    // against the live list, so a sheet whose message vanished closes itself.
    var actionsMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingBlock by rememberSaveable { mutableStateOf(false) }

    // Clear the draft only once a send succeeds; keep it on failure.
    LaunchedEffect(sendStatus) {
        if (awaitingSend && sendStatus == DmSendStatus.Idle) {
            draft = ""
            awaitingSend = false
        } else if (sendStatus is DmSendStatus.Failed) {
            awaitingSend = false
        }
    }

    AeroPage(
        title = otherName ?: stringResource(R.string.dm_unknownMember),
        // The IME *and* the navigation-bar inset (their union, so the taller of
        // the two wins rather than double-counting), matching the group channels'
        // composer: keyboard down the input clears the nav bar, keyboard up it
        // lifts above the IME. Plain imePadding() left the input under the nav bar
        // whenever the keyboard was down.
        modifier = modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        scrollable = false,
        onTitleClick = onViewProfile,
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (threadLoading && messages.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (messages.isEmpty()) {
                Text(
                    text = stringResource(R.string.dm_emptyThread),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                MessageList(
                    messages = messages,
                    currentUid = currentUid,
                    canLoadOlder = canLoadOlder,
                    isLoadingOlder = isLoadingOlder,
                    onLoadOlder = onLoadOlder,
                    // Long-press opens the moderation sheet — never on your own
                    // message, and never when the thread has no resolvable other
                    // member to act on.
                    onMessageLongPress = { message ->
                        if (MessageModeration.canActOn(otherUid, currentUid) &&
                            message.senderUid != currentUid &&
                            MessageModeration.hasActions(
                                canBlock = onBlock != null,
                                reportAvailability =
                                    MessageModeration.reportAvailability(ChatSurface.DirectMessage),
                            )
                        ) {
                            actionsMessageId = message.id
                        }
                    },
                )
            }
        }

        if (sendStatus is DmSendStatus.Failed) {
            Text(
                text = stringResource(sendStatus.error.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (blockStatus == BlockActionStatus.Failed) {
            Text(
                text = stringResource(R.string.blocking_errorGeneric),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (blockStatus == BlockActionStatus.Done) {
            Text(
                text = stringResource(R.string.blocking_blockSuccess),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    if (it.length <= DM_MESSAGE_MAX_LENGTH) draft = it
                    if (sendStatus is DmSendStatus.Failed) onResetError()
                },
                placeholder = { Text(stringResource(R.string.dm_inputPlaceholder)) },
                modifier = Modifier.weight(1f),
                singleLine = false,
            )
            Button(
                onClick = {
                    awaitingSend = true
                    onSend(draft)
                },
                enabled = sendStatus != DmSendStatus.Sending && DmThread.isSendable(draft),
            ) {
                Text(stringResource(R.string.dm_send))
            }
        }
    }

    if (messages.any { it.id == actionsMessageId }) {
        MessageActionsSheet(
            memberName = otherName,
            canBlock = onBlock != null,
            reportAvailability = MessageModeration.reportAvailability(ChatSurface.DirectMessage),
            onBlock = {
                actionsMessageId = null
                confirmingBlock = true
            },
            // Unreachable while DMs are BackendMissing (the row is disabled), but
            // wired so a `dm.reportMessage` callable only needs a submit lambda.
            onReport = { actionsMessageId = null },
            onDismiss = { actionsMessageId = null },
        )
    }

    if (confirmingBlock) {
        BlockConfirmDialog(
            memberName = otherName,
            onConfirm = {
                confirmingBlock = false
                onBlock?.invoke(otherUid)
            },
            onDismiss = {
                confirmingBlock = false
                onBlockDismiss()
            },
        )
    }
}

@Composable
private fun MessageList(
    messages: List<DmMessage>,
    currentUid: String,
    canLoadOlder: Boolean,
    isLoadingOlder: Boolean,
    onLoadOlder: () -> Unit,
    onMessageLongPress: (DmMessage) -> Unit,
) {
    val dates = rememberChatDateContext()
    // Day separators are inserted by pure logic over the WHOLE list (see
    // ChatTimeline) — including after an older page is prepended, which is what
    // keeps the pagination seam from growing a duplicate or losing a heading.
    val timeline =
        remember(messages, dates.zone) {
            ChatTimeline.build(
                messages = messages,
                zone = dates.zone,
                id = { it.id },
                timestampMillis = { it.createdAtMillis },
            )
        }
    val listState = rememberLazyListState()
    // Is the soft keyboard up? Read as a plain inset rather than the experimental
    // `WindowInsets.isImeVisible`. A LazyColumn holds its scroll OFFSET, not its
    // bottom edge, so when the composer's ime padding shrinks the viewport the
    // newest message would otherwise slide out under the keyboard.
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    // derivedStateOf so the per-frame inset animation doesn't recompose the list
    // 60 times a second — only the two transitions (up / down) propagate.
    val imeVisible by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    // The optional "load older" row is a single item prepended before the
    // messages, so every message's LazyColumn index is shifted by +1 while it is
    // present. Track that offset so the auto-scroll targets the real last item.
    val headerOffset = if (canLoadOlder || isLoadingOlder) 1 else 0
    // Auto-scroll to the newest message only when it won't fight the reader:
    // either the new message is the user's OWN send (always follow your own
    // message), or the user is already at/near the bottom. If they've scrolled
    // up to read older messages and an incoming message arrives, leave them put.
    LaunchedEffect(messages.lastOrNull()?.id) {
        val newest = messages.lastOrNull() ?: return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val totalItems = layoutInfo.totalItemsCount
        // Not laid out yet (first load) → scroll; otherwise near the bottom.
        val nearBottom = totalItems == 0 || lastVisibleIndex >= totalItems - 2
        val isOwnSend = newest.senderUid == currentUid
        if (isOwnSend || nearBottom) {
            // Index into the LAZY COLUMN, whose rows are the TIMELINE (messages
            // interleaved with day separators), not the raw message list — plus
            // the optional "load older" header. Using messages.lastIndex here
            // would land short by one row per separator.
            listState.animateScrollToItem(timeline.lastIndex + headerOffset)
        }
    }

    // Keyboard just came up: the viewport shrank from the bottom, so re-pin the
    // newest message — but only for a reader who was already at the bottom, so
    // tapping the composer while reading history doesn't yank them down. Guarded
    // on the RISING edge, so closing the keyboard (which grows the viewport back)
    // moves nothing.
    LaunchedEffect(imeVisible) {
        if (!imeVisible) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val totalItems = layoutInfo.totalItemsCount
        if (totalItems == 0) return@LaunchedEffect
        if (lastVisibleIndex >= totalItems - 2) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        if (canLoadOlder || isLoadingOlder) {
            item(key = "load-older") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (isLoadingOlder) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        TextButton(onClick = onLoadOlder) {
                            Text(stringResource(R.string.dm_loadOlder))
                        }
                    }
                }
            }
        }
        items(timeline, key = { it.key }) { item ->
            when (item) {
                is ChatTimelineItem.DaySeparator -> DaySeparatorRow(date = item.date, dates = dates)
                is ChatTimelineItem.Message -> {
                    val message = item.message
                    val isOwn = message.senderUid == currentUid
                    MessageBubble(
                        message = message,
                        isOwn = isOwn,
                        dates = dates,
                        // Your own bubble carries no long-press: you can neither
                        // block nor report yourself.
                        onLongPress = if (isOwn) null else ({ onMessageLongPress(message) }),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: DmMessage,
    isOwn: Boolean,
    dates: ChatDateContext,
    onLongPress: (() -> Unit)?,
) {
    val bubbleColor =
        if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (isOwn) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    // The time sits on the line ABOVE the bubble, aligned to the bubble's own
    // edge (right for your messages, left for theirs) — the same placement the
    // group channels use, where it shares the sender-name header line. A 1:1
    // thread has no sender header, so the time gets that line to itself.
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
    ) {
        MessageTimeText(millis = message.createdAtMillis, dates = dates)
        Surface(
            color = bubbleColor,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KccRadius.lg),
            modifier =
                Modifier
                    .widthIn(max = 280.dp)
                    .then(
                        if (onLongPress != null) {
                            // combinedClickable, not pointerInput: it announces the
                            // long-press to accessibility services as a custom
                            // action. onClick is a deliberate no-op — a DM bubble has
                            // no tap action.
                            Modifier.combinedClickable(onLongClick = onLongPress, onClick = {})
                        } else {
                            Modifier
                        },
                    ),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s3),
            )
        }
    }
}

/** The `dm.*` send-error string for a mapped [DmSendError]. */
private fun DmSendError.messageRes(): Int =
    when (this) {
        DmSendError.SignedOut -> R.string.dm_sendErrorSignedOut
        DmSendError.NotMember -> R.string.dm_sendErrorNotMember
        DmSendError.Invalid -> R.string.dm_sendErrorInvalid
        DmSendError.CannotDeliver -> R.string.dm_sendErrorCannotDeliver
        DmSendError.Generic -> R.string.dm_sendErrorGeneric
    }
