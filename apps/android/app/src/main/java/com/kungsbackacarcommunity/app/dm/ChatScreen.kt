package com.kungsbackacarcommunity.app.dm

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.kungsbackacarcommunity.app.chat.KeepPinnedToNewest
import com.kungsbackacarcommunity.app.chat.RepinToNewestOnImeRise
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
import com.kungsbackacarcommunity.app.shell.AeroPage

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
    onSend: (String) -> Unit,
    onRetry: (DmMessage) -> Unit,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
    onViewProfile: (() -> Unit)? = null,
    otherUid: String = "",
    onBlock: ((String) -> Unit)? = null,
    blockStatus: BlockActionStatus = BlockActionStatus.Idle,
    onBlockDismiss: () -> Unit = {},
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

        // One-tap emoji reactions, pinned directly above the input. Tapping one
        // sends it straight down the SAME optimistic path as the Send button — the
        // emoji is the whole message and the typed draft (if any) is left untouched.
        // Always enabled: the DM composer's only send gate is a non-empty draft,
        // which a one-tap emoji doesn't need.
        ChatQuickEmojiRow(
            onEmojiSelected = { glyph -> onSend(glyph) },
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
                    // is no in-flight disabled/spinner state to wait through.
                    onSend(draft)
                    draft = ""
                },
                enabled = DmThread.isSendable(draft),
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
    onRetry: (DmMessage) -> Unit,
    onMessageLongPress: (DmMessage) -> Unit,
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)?,
    onOpenEvent: ((String) -> Unit)?,
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
                        onRetry = { onRetry(message) },
                        onShowLocationOnMap = onShowLocationOnMap,
                        onOpenEvent = onOpenEvent,
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
    val body =
        remember(message.text, linkColor, linkLabel, eventLinkLabel, canShowLocation, canOpenEvent) {
            annotateChatLinks(
                text = message.text,
                linkColor = linkColor,
                locationLabel = linkLabel,
                eventLabel = eventLinkLabel,
                onShowLocationOnMap = onShowLocationOnMap,
                onOpenEvent = onOpenEvent,
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
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s3),
            )
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

/** One tappable link found in a message body — a `geo:` map link or a `kccevent:` event link. */
private sealed interface ChatLinkMatch {
    val range: IntRange

    data class Location(override val range: IntRange, val latitude: Double, val longitude: Double) :
        ChatLinkMatch

    data class Event(override val range: IntRange, val eventId: String) : ChatLinkMatch
}

/**
 * Replaces each recognised link token in [text] with a single tappable chip,
 * leaving the rest of the message as plain text:
 *  - a `geo:lat,lng` token → a [locationLabel] chip that moves the app's map to the
 *    point (only when [onShowLocationOnMap] is wired);
 *  - a `kccevent:<id>` token → an [eventLabel] chip that opens that event's detail
 *    page (only when [onOpenEvent] is wired).
 *
 * Uses the SAME [GeoLinks.findAll] / [EventShareLinks.findAll] parsers as the
 * writers, so a shared location or event reads identically everywhere and a
 * malformed token is never linkified. When neither handler is wired the raw text is
 * returned untouched. Matches are rendered in document order; the two token schemes
 * are disjoint (`geo:` vs `kccevent:`) so their ranges can never overlap.
 */
private fun annotateChatLinks(
    text: String,
    linkColor: Color,
    locationLabel: String,
    eventLabel: String,
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)?,
    onOpenEvent: ((String) -> Unit)?,
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
    }.sortedBy { it.range.first }
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
