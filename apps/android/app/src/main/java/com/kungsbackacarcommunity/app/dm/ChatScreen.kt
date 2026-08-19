package com.kungsbackacarcommunity.app.dm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.chat.ChatLinkSpans
import com.kungsbackacarcommunity.app.chat.ChatQuoteHeader
import com.kungsbackacarcommunity.app.chat.ChatReply
import com.kungsbackacarcommunity.app.chat.ChatUrlOpener
import com.kungsbackacarcommunity.app.chat.KeepPinnedToNewest
import com.kungsbackacarcommunity.app.chat.RepinToNewestOnImeRise
import com.kungsbackacarcommunity.app.chat.ReplyComposerChip
import com.kungsbackacarcommunity.app.chat.WebLinks
import com.kungsbackacarcommunity.app.chattime.ChatDateContext
import com.kungsbackacarcommunity.app.chattime.ChatTimeline
import com.kungsbackacarcommunity.app.chattime.ChatTimelineItem
import com.kungsbackacarcommunity.app.chattime.DaySeparatorRow
import com.kungsbackacarcommunity.app.chattime.MessageTimeText
import com.kungsbackacarcommunity.app.chattime.rememberChatDateContext
import com.kungsbackacarcommunity.app.design.ChatComposerKeyboardOptions
import com.kungsbackacarcommunity.app.design.ChatQuickEmojiRow
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.events.EventShareLinks
import com.kungsbackacarcommunity.app.location.GeoLinks
import com.kungsbackacarcommunity.app.moderation.BlockConfirmDialog
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import com.kungsbackacarcommunity.app.moderation.MessageActionsSheet
import com.kungsbackacarcommunity.app.moderation.MessageModeration
import com.kungsbackacarcommunity.app.moderation.ReportAvailability
import com.kungsbackacarcommunity.app.shell.AeroPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A 1:1 DM thread: own vs other message bubbles (chronological, newest at the
 * bottom), an optional "load earlier" affordance at the top, and a text input +
 * send. Stateless apart from the message draft.
 *
 * Send is optimistic: on tap the draft clears immediately and the message
 * appears at once as a "sending" bubble (the caller-side [DmMessage] already
 * carries [DmDeliveryState]) — there is no wait for the network round-trip. A
 * bubble that fails to send shows a tappable "tap to retry" affordance ([onRetry]);
 * the user's text is never lost.
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
    canLoadOlder: Boolean,
    isLoadingOlder: Boolean,
    onSend: (String, DmReplyTo?) -> Unit,
    onRetry: (DmMessage) -> Unit,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
    onViewProfile: (() -> Unit)? = null,
    otherUid: String = "",
    onBlock: ((String) -> Unit)? = null,
    blockStatus: BlockActionStatus = BlockActionStatus.Idle,
    onBlockDismiss: () -> Unit = {},
    // The `chatReplies` flag: gates the inline-reply entry point (long-press
    // "Svara" + the composer quote chip). Off leaves the thread exactly as before.
    chatRepliesEnabled: Boolean = false,
    // A shared `geo:` link in a message becomes a tappable "show on map" chip that
    // calls this. Null (no map to move) leaves such links as plain text — the same
    // rule the group channels use.
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)? = null,
    // A shared `kccevent:` link in a message becomes a tappable "Open event" chip
    // that calls this with the event id. Null leaves such tokens as plain text.
    onOpenEvent: ((String) -> Unit)? = null,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    // Held by message ID (Saveable, so the sheet survives rotation) and resolved
    // against the live list, so a sheet whose message vanished closes itself.
    var actionsMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingBlock by rememberSaveable { mutableStateOf(false) }
    // The message being replied to (Saveable), resolved against the live list so
    // the composer quote chip clears itself if its target leaves the thread.
    var replyToId by rememberSaveable { mutableStateOf<String?>(null) }

    AeroPage(
        title = otherName ?: stringResource(R.string.dm_unknownMember),
        modifier = modifier,
        scrollable = false,
        onTitleClick = onViewProfile,
        // The IME *and* the navigation-bar inset (their union, so the taller of
        // the two wins rather than double-counting), matching the group channels'
        // composer: keyboard down the input clears the nav bar, keyboard up it
        // lifts above the IME. Plain imePadding() left the input under the nav bar
        // whenever the keyboard was down.
        //
        // Passed as contentWindowInsets rather than folded into `modifier`: the
        // page's background Surface draws at its own node's size, so padding in
        // its modifier chain would shrink the background and leave a bare band
        // under the transparent nav bar. AeroPage applies this inside the Surface,
        // where it already applies the status-bar inset for the same reason.
        contentWindowInsets = WindowInsets.ime.union(WindowInsets.navigationBars),
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
                    onRetry = onRetry,
                    onShowLocationOnMap = onShowLocationOnMap,
                    onOpenEvent = onOpenEvent,
                    chatRepliesEnabled = chatRepliesEnabled,
                    counterpartyName = otherName,
                    // Long-press opens the shared context menu. It opens whenever
                    // there is ANY action: Reply (on any message, incl. your own,
                    // while the flag is on) OR a moderation action on the other
                    // member's message. With replies off and no moderation action
                    // it does nothing, as before.
                    onMessageLongPress = { message ->
                        val moderationActions =
                            MessageModeration.canActOn(otherUid, currentUid) &&
                                message.senderUid != currentUid &&
                                MessageModeration.hasActions(
                                    canBlock = onBlock != null,
                                    reportAvailability =
                                        MessageModeration.reportAvailability(
                                            ChatSurface.DirectMessage,
                                        ),
                                )
                        if (chatRepliesEnabled || moderationActions) {
                            actionsMessageId = message.id
                        }
                    },
                )
            }
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

        // Resolve the reply target against the LIVE list: a target that leaves the
        // thread simply clears the chip. The client-side quote snapshot is built
        // once so both send paths (Send button + quick emoji) attach the same one;
        // the delivered document carries the server's authoritative snapshot.
        val replyTarget =
            if (chatRepliesEnabled) messages.firstOrNull { it.id == replyToId } else null
        LaunchedEffect(replyToId, replyTarget == null) {
            if (replyToId != null && replyTarget == null) replyToId = null
        }
        val replySnapshot =
            replyTarget?.let {
                DmReplyTo(
                    messageId = it.id,
                    senderUid = it.senderUid,
                    // In a 1:1 thread every incoming message is from the titled
                    // other member; own replies quote the caller. The name shown on
                    // the chip resolves the counterparty via [otherName].
                    senderDisplayName = if (it.senderUid == currentUid) null else otherName,
                    textPreview = ChatReply.quotePreview(it.text),
                )
            }
        if (replyTarget != null) {
            ReplyComposerChip(
                authorName =
                    (replySnapshot?.senderDisplayName ?: otherName)
                        ?: stringResource(R.string.dm_unknownMember),
                preview = replySnapshot?.textPreview.orEmpty(),
                onCancel = { replyToId = null },
            )
        }

        // One-tap emoji reactions, pinned directly above the input. Tapping one
        // sends it straight down the SAME optimistic path as the Send button — the
        // emoji is the whole message and the typed draft (if any) is left untouched.
        // Always enabled: the DM composer's only send gate is a non-empty draft,
        // which a one-tap emoji doesn't need. A pending reply target rides along
        // and is then cleared, so the emoji quotes what you replied to.
        ChatQuickEmojiRow(
            onEmojiSelected = { glyph ->
                onSend(glyph, replySnapshot)
                replyToId = null
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    if (it.length <= DM_MESSAGE_MAX_LENGTH) draft = it
                },
                placeholder = { Text(stringResource(R.string.dm_inputPlaceholder)) },
                modifier = Modifier.weight(1f),
                keyboardOptions = ChatComposerKeyboardOptions,
                singleLine = false,
            )
            Button(
                onClick = {
                    // Optimistic: hand the draft off and clear the input at once.
                    // The message appears immediately as a "sending" bubble; there
                    // is no in-flight disabled/spinner state to wait through. Any
                    // pending reply target rides along and is then cleared.
                    onSend(draft, replySnapshot)
                    draft = ""
                    replyToId = null
                },
                enabled = DmThread.isSendable(draft),
            ) {
                Text(stringResource(R.string.dm_send))
            }
        }
    }

    val actionsMessage = messages.firstOrNull { it.id == actionsMessageId }
    if (actionsMessage != null) {
        // Block/report target the OTHER member, so they only apply to an incoming
        // message; the sheet can now also open on your own DM (for Reply), so they
        // are gated here. A blank otherUid (malformed thread) also disables block.
        val canModerate =
            actionsMessage.senderUid != currentUid &&
                MessageModeration.canActOn(otherUid, currentUid)
        MessageActionsSheet(
            memberName = otherName,
            canReply = chatRepliesEnabled,
            onReply = {
                replyToId = actionsMessage.id
                actionsMessageId = null
            },
            canBlock = onBlock != null && canModerate,
            reportAvailability =
                if (canModerate) {
                    MessageModeration.reportAvailability(ChatSurface.DirectMessage)
                } else {
                    ReportAvailability.BackendMissing
                },
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
    onRetry: (DmMessage) -> Unit,
    onMessageLongPress: (DmMessage) -> Unit,
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)?,
    onOpenEvent: ((String) -> Unit)?,
    chatRepliesEnabled: Boolean,
    counterpartyName: String?,
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
    // On open, JUMP straight to the newest message; thereafter follow new messages
    // down to the bottom, but only when it won't fight a reader scrolled up into
    // history. Targets the LazyColumn's last item directly (totalItemsCount - 1),
    // so day separators and the optional "load older" header need no offset
    // bookkeeping here. Shared with the group channels and event chat.
    val newest = messages.lastOrNull()
    KeepPinnedToNewest(
        listState = listState,
        newestMessageId = newest?.id,
        isOwnNewestMessage = newest?.senderUid == currentUid,
    )

    // Keyboard just came up: the viewport shrank from the bottom, so re-pin the
    // newest message — shared with the group channels and event chat so the three
    // surfaces cannot drift apart.
    RepinToNewestOnImeRise(listState)

    // Tapping a reply's quote header scrolls to and briefly highlights the ORIGINAL
    // message — but only when it is still in the loaded window; a quoted parent
    // that has left it simply does nothing (the snapshot on the reply still shows).
    val scope = rememberCoroutineScope()
    var highlightedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedId) {
        if (highlightedId != null) {
            delay(1500)
            highlightedId = null
        }
    }
    val headerOffset = if (canLoadOlder || isLoadingOlder) 1 else 0
    val onQuoteTap: (String) -> Unit = { parentId ->
        val index =
            ChatReply.indexOfMessage(timeline, parentId) { item ->
                (item as? ChatTimelineItem.Message)?.message?.id.orEmpty()
            }
        if (index != null) {
            highlightedId = parentId
            scope.launch { listState.animateScrollToItem((index + headerOffset).coerceAtLeast(0)) }
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
                        // Your own bubble now carries a long-press too — but only
                        // for Reply, and only while that feature is on. Block/report
                        // never applied to your own message and still don't.
                        onLongPress =
                            if (isOwn) {
                                ({ onMessageLongPress(message) }).takeIf { chatRepliesEnabled }
                            } else {
                                { onMessageLongPress(message) }
                            },
                        onRetry = { onRetry(message) },
                        onShowLocationOnMap = onShowLocationOnMap,
                        onOpenEvent = onOpenEvent,
                        highlighted = message.id == highlightedId,
                        onQuoteTap = onQuoteTap,
                        currentUid = currentUid,
                        counterpartyName = counterpartyName,
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
    onRetry: () -> Unit,
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)?,
    onOpenEvent: ((String) -> Unit)?,
    highlighted: Boolean = false,
    onQuoteTap: (String) -> Unit = {},
    currentUid: String = "",
    counterpartyName: String? = null,
) {
    val bubbleColor =
        if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (isOwn) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    // A shared `geo:` link renders as a single tappable "show on map" chip —
    // coloured against the bubble and underlined so it reads as tappable. Detection
    // is keyed on WHETHER a map-move handler exists (not the lambda identity) so the
    // AnnotatedString is not rebuilt every recompose. Mirrors the group channels.
    val linkColor = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val linkLabel = stringResource(R.string.channel_locationLink)
    val eventLinkLabel = stringResource(R.string.events_shareEventLinkLabel)
    val canShowLocation = onShowLocationOnMap != null
    val canOpenEvent = onOpenEvent != null
    // Tapping an auto-linkified http/https URL opens it in the phone's DEFAULT
    // browser (an ACTION_VIEW intent the OS resolves), guarded against a missing
    // browser. The handler captures the (stable) context, so it is deliberately NOT
    // a remember key below — only the message content drives a rebuild.
    val context = LocalContext.current
    val onOpenUrl: (String) -> Unit = remember(context) { { url -> ChatUrlOpener.open(context, url) } }
    val body =
        remember(message.text, linkColor, linkLabel, eventLinkLabel, canShowLocation, canOpenEvent) {
            annotateChatLinks(
                text = message.text,
                linkColor = linkColor,
                locationLabel = linkLabel,
                eventLabel = eventLinkLabel,
                onShowLocationOnMap = onShowLocationOnMap,
                onOpenEvent = onOpenEvent,
                onOpenUrl = onOpenUrl,
            )
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
        val bubbleShape = androidx.compose.foundation.shape.RoundedCornerShape(KccRadius.lg)
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            modifier =
                Modifier
                    .widthIn(max = 280.dp)
                    // A brief outline while this message is the tap-to-scroll target
                    // of a quote header, so the reader's eye lands on the original.
                    .then(
                        if (highlighted) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, bubbleShape)
                        } else {
                            Modifier
                        },
                    )
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
            // Bubble layout is [optional quote header] · [text] · [reserved
            // reactions row]: the pieces stack in a Column so a message-REACTIONS
            // fast-follow is purely additive — a reactions row slots in after the
            // text with no change to the quote header or text above it.
            Column(
                modifier = Modifier.padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s3),
                verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
            ) {
                message.replyTo?.let { reply ->
                    val quoteAuthor =
                        reply.senderDisplayName?.takeIf { it.isNotBlank() }
                            ?: counterpartyName?.takeIf { reply.senderUid != currentUid }
                            ?: stringResource(R.string.dm_unknownMember)
                    ChatQuoteHeader(
                        authorName = quoteAuthor,
                        preview = reply.textPreview,
                        isOwn = isOwn,
                        onClick = { onQuoteTap(reply.messageId) },
                    )
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    textAlign = TextAlign.Start,
                )
                // Reserved slot for the future message-reactions row (a reactions
                // fast-follow attaches here, keyed off this message's stable id).
            }
        }
        // Delivery status sits under your OWN optimistic bubbles only. A delivered
        // (server-sourced) message is [DmDeliveryState.Sent] and shows nothing.
        if (isOwn) {
            when (message.deliveryState) {
                DmDeliveryState.Sending ->
                    Text(
                        text = stringResource(R.string.dm_statusSending),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                DmDeliveryState.Failed -> {
                    val retryable = message.sendError?.isRetryable ?: true
                    Text(
                        // Retryable (transient) failures invite a resend; terminal
                        // ones (signed out / not a member / cannot deliver) show the
                        // specific reason instead of a "retry" that would just fail.
                        text =
                            if (retryable) {
                                stringResource(R.string.dm_statusFailedRetry)
                            } else {
                                stringResource(message.sendError!!.messageRes())
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            if (retryable) {
                                // clickable BEFORE padding so the whole padded area
                                // is the tap target (a comfortable hit region), and
                                // the resend reuses the SAME idempotency key so it
                                // never double-posts.
                                Modifier.clickable(role = Role.Button, onClick = onRetry)
                                    .padding(vertical = KccSpacing.s2)
                            } else {
                                Modifier
                            },
                    )
                }
                DmDeliveryState.Sent -> Unit
            }
        }
    }
}

/**
 * One tappable link found in a message body — a `geo:` map link, a `kccevent:` event
 * link, or a plain `http(s)://` web URL the sender pasted.
 */
private sealed interface ChatLinkMatch {
    val range: IntRange

    data class Location(override val range: IntRange, val latitude: Double, val longitude: Double) :
        ChatLinkMatch

    data class Event(override val range: IntRange, val eventId: String) : ChatLinkMatch

    data class Web(override val range: IntRange, val url: String) : ChatLinkMatch
}

/**
 * Replaces each recognised link token in [text] with a single tappable chip,
 * leaving the rest of the message as plain text:
 *  - a `geo:lat,lng` token → a [locationLabel] chip that moves the app's map to the
 *    point (only when [onShowLocationOnMap] is wired);
 *  - a `kccevent:<id>` token → an [eventLabel] chip that opens that event's detail
 *    page (only when [onOpenEvent] is wired).
 *
 * Uses the SAME [GeoLinks.findAll] / [EventShareLinks.findAll] / [WebLinks.findAll]
 * parsers as the writers, so a shared location, event, or pasted URL reads
 * identically everywhere and a malformed token is never linkified. A pasted
 * `http(s)://` URL becomes a tappable link that opens the phone's default browser via
 * [onOpenUrl] (http/https ONLY — `tel:`/`intent:`/`javascript:`/`file:` are never
 * linkified).
 *
 * The three matchers can produce OVERLAPPING ranges — a web URL may contain a
 * `geo:`/`kccevent:`-looking substring in its path that those matchers also flag —
 * so [ChatLinkSpans.nonOverlapping] reconciles them to a strictly non-overlapping,
 * ascending set (the outer URL wins) before rendering. The append loop then only ever
 * advances, so nothing is duplicated or misordered.
 */
private fun annotateChatLinks(
    text: String,
    linkColor: Color,
    locationLabel: String,
    eventLabel: String,
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)?,
    onOpenEvent: ((String) -> Unit)?,
    onOpenUrl: (String) -> Unit,
): AnnotatedString {
    val matches = buildList {
        if (onShowLocationOnMap != null) {
            GeoLinks.findAll(text).forEach {
                add(ChatLinkMatch.Location(it.range, it.link.latitude, it.link.longitude))
            }
        }
        if (onOpenEvent != null) {
            EventShareLinks.findAll(text).forEach {
                add(ChatLinkMatch.Event(it.range, it.link.eventId))
            }
        }
        WebLinks.findAll(text).forEach {
            add(ChatLinkMatch.Web(it.range, it.link.url))
        }
    }.let { ChatLinkSpans.nonOverlapping(it) { m -> m.range } }
    if (matches.isEmpty()) return AnnotatedString(text)

    val linkStyles =
        TextLinkStyles(
            style =
                SpanStyle(
                    color = linkColor,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                ),
        )
    return buildAnnotatedString {
        var index = 0
        for (match in matches) {
            if (match.range.first > index) append(text.substring(index, match.range.first))
            when (match) {
                is ChatLinkMatch.Location ->
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "geo",
                            styles = linkStyles,
                            linkInteractionListener = {
                                onShowLocationOnMap?.invoke(match.latitude, match.longitude)
                            },
                        ),
                    ) {
                        append(locationLabel)
                    }

                is ChatLinkMatch.Event ->
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "event",
                            styles = linkStyles,
                            linkInteractionListener = { onOpenEvent?.invoke(match.eventId) },
                        ),
                    ) {
                        append(eventLabel)
                    }

                is ChatLinkMatch.Web ->
                    withLink(
                        LinkAnnotation.Url(
                            url = match.url,
                            styles = linkStyles,
                            linkInteractionListener = { onOpenUrl(match.url) },
                        ),
                    ) {
                        append(match.url)
                    }
            }
            index = match.range.last + 1
        }
        if (index < text.length) append(text.substring(index))
    }
}

/** The specific `dm.*` reason string for a terminal (non-retryable) send failure. */
private fun DmSendError.messageRes(): Int =
    when (this) {
        DmSendError.SignedOut -> R.string.dm_sendErrorSignedOut
        DmSendError.NotMember -> R.string.dm_sendErrorNotMember
        DmSendError.Invalid -> R.string.dm_sendErrorInvalid
        DmSendError.CannotDeliver -> R.string.dm_sendErrorCannotDeliver
        DmSendError.Generic -> R.string.dm_sendErrorGeneric
    }
