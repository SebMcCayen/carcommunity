package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.chat.ChatUrlOpener
import com.kungsbackacarcommunity.app.chat.KeepPinnedToNewest
import com.kungsbackacarcommunity.app.chat.RepinToNewestOnImeRise
import com.kungsbackacarcommunity.app.chat.WebLinks
import com.kungsbackacarcommunity.app.chattime.ChatDateContext
import com.kungsbackacarcommunity.app.chattime.ChatTimeline
import com.kungsbackacarcommunity.app.chattime.ChatTimelineItem
import com.kungsbackacarcommunity.app.chattime.DaySeparatorRow
import com.kungsbackacarcommunity.app.chattime.MessageTimeText
import com.kungsbackacarcommunity.app.chattime.rememberChatDateContext
import com.kungsbackacarcommunity.app.design.ChatComposerKeyboardOptions
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.location.GeoLinks
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.moderation.BlockConfirmDialog
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import com.kungsbackacarcommunity.app.moderation.MessageActionsSheet
import com.kungsbackacarcommunity.app.moderation.MessageModeration

/** Test tag for the message input (Compose tests type into it to drive the picker). */
const val CHANNEL_INPUT_TEST_TAG = "channel-input"

/** Test tag for the @-autocomplete list; present only while a query is live. */
const val MENTION_PICKER_TEST_TAG = "mention-picker"

/** Test tag for the "you can mention at most 10" note. */
const val MENTION_CAP_TEST_TAG = "mention-cap-note"

/** Test tag for the "some mentions weren't delivered" note. */
const val MENTION_DROPPED_TEST_TAG = "mention-dropped-note"

/** Test tag of one picker row (keyed by uid — display names are not unique). */
fun mentionCandidateTestTag(uid: String): String = "mention-candidate-$uid"

/**
 * ASCII unit separator, the [MentionSpansSaver] field delimiter.
 *
 * Two of the three encoded fields provably cannot contain it: a uid is
 * `[A-Za-z0-9._-]+` (the backend's own id schema, functions/src/chatchannels/
 * chat-core.ts), and an offset is decimal digits. A LABEL can — it is "@" plus a
 * display name, and the backend constrains a display name's length but not its
 * charset, so nothing stops one holding a control character. Hence the field
 * order and the bounded split in [MentionSpansSaver]: the two constrained fields
 * go first, and the unconstrained one takes the remainder.
 */
private const val MENTION_SEPARATOR = '\u001F'

/**
 * Saves the draft's mention spans across configuration change / process death,
 * alongside the draft text itself. Flattened to strings because a
 * [rememberSaveable] Bundle takes no arbitrary data classes.
 *
 * Encoded `uid<sep>start<sep>label` and split with `limit = 3`, which makes the
 * encoding TOTAL: uid and start cannot contain the separator, so the third field
 * is simply whatever remains and a label round-trips whatever it holds. The
 * obvious `uid<sep>label<sep>start` is PARTIAL — a separator anywhere in the
 * display name splits into four parts and silently drops the span, losing a
 * mention over what someone called themselves.
 *
 * A row that still doesn't round-trip is dropped — that costs a mention rather
 * than restoring a half-parsed one, and the composer re-verifies every restored
 * span against the text on the next edit regardless.
 */
internal val MentionSpansSaver: Saver<List<MentionSpan>, Any> =
    Saver(
        save = { spans ->
            ArrayList(
                spans.map { span ->
                    listOf(span.uid, span.start.toString(), span.label)
                        .joinToString(MENTION_SEPARATOR.toString())
                },
            )
        },
        restore = { saved ->
            @Suppress("UNCHECKED_CAST")
            (saved as? List<String>)?.mapNotNull { encoded ->
                val parts = encoded.split(MENTION_SEPARATOR, limit = 3)
                val start = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size == 3 && start != null) {
                    MentionSpan(uid = parts[0], label = parts[2], start = start)
                } else {
                    null
                }
            }
        },
    )

/**
 * Shared group-channel chat body (community + convoy): a message list with the
 * caller's own vs others' bubbles (others carry the sender's avatar + name, since
 * a channel has an unbounded roster), an optional "load earlier" affordance at
 * the top, and a text input + send pinned to the bottom. Stateless apart from the
 * draft. Unlike the DM [ChatScreen] this is NOT wrapped in the Aero page chrome —
 * it fills whatever container the chat hub gives it (below the hub's tab row).
 *
 * Send is OPTIMISTIC (mirrors the DM thread): on tap the draft clears immediately
 * and the message appears at once as a "sending" bubble — there is no wait for the
 * `*-post` round-trip. A bubble that fails to send shows a tappable "tap to retry"
 * affordance ([onRetry]); the user's text is never lost. The caller merges its own
 * pending bubbles into [messages] via [ChannelThread.mergeWithPending].
 *
 * @MENTIONS are COMMUNITY-only, switched on by a non-empty [mentionCandidates].
 * Convoy passes none and gets no picker: per the backend, convoyChat-post accepts
 * no mentions and every convoy message stores `mentionedUids: []`, so there is
 * nothing there to pick or to highlight.
 *
 * @param mentionCandidates members the @-picker may insert (already excludes the
 *   caller). Empty disables the picker entirely.
 * @param mentionDisplayNames uid → display name for HIGHLIGHTING. Highlighting
 *   resolves only what this map contains; a mentioned uid absent from it renders
 *   unhighlighted (its member was still notified — see [MentionRendering]).
 *   Callers are expected to pass a SUPERSET of [mentionCandidates], one that
 *   still includes the caller: the picker excludes you, but being mentioned
 *   yourself must highlight, and only the caller can supply that name.
 * @param droppedMentionCount mentions the server dropped from the last send (see
 *   [ChannelChatCoordinator.droppedMentionCount]); > 0 shows one quiet note.
 * @param onViewProfile opens the read-only member profile for a sender's uid.
 *   Null (the default) leaves the sender header inert — the config-less build has
 *   no profile repository to open. Only OTHER members' messages carry a sender
 *   header at all, so the caller's own messages can never navigate here.
 * @param surface which channel this is. It decides whether the long-press action
 *   sheet's "Report message" can reach a backend
 *   ([MessageModeration.reportAvailability]) — neither channel has a report
 *   callable today, so the row is omitted. Deliberately has NO default: a caller
 *   that forgets it would silently pick some other channel's report wiring. See
 *   [MessageModeration] for the precise gap.
 * @param onBlock blocks a sender's uid. Null (the default) means blocking is
 *   unwired (config-less build), and the sheet then omits the block row.
 */
@Composable
fun ChannelChatContent(
    messages: List<ChannelMessage>,
    currentUid: String,
    loading: Boolean,
    emptyText: String,
    canLoadOlder: Boolean,
    isLoadingOlder: Boolean,
    onSend: (String, List<String>) -> Unit,
    onRetry: (ChannelMessage) -> Unit,
    onLoadOlder: () -> Unit,
    surface: ChatSurface,
    modifier: Modifier = Modifier,
    mentionCandidates: List<MentionCandidate> = emptyList(),
    mentionDisplayNames: Map<String, String> = emptyMap(),
    droppedMentionCount: Int = 0,
    onDismissDroppedMentions: () -> Unit = {},
    onViewProfile: ((String) -> Unit)? = null,
    onBlock: ((String) -> Unit)? = null,
    blockStatus: BlockActionStatus = BlockActionStatus.Idle,
    onBlockDismiss: () -> Unit = {},
    // Tapping a shared `geo:` location link in a message moves the app's OWN map
    // to that point, IN-APP — the recipient's map camera, never an external maps
    // chooser. Null (the default, and in a config-less build with no map to move)
    // renders a location link as plain, unclickable styled text.
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)? = null,
) {
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    // The uids the picker resolved, each welded to the exact text it inserted.
    // Held beside the draft rather than inside the TextFieldValue so the entire
    // tracking model stays pure and unit-testable (see DraftMentions).
    var mentions by rememberSaveable(stateSaver = MentionSpansSaver) {
        mutableStateOf(emptyList<MentionSpan>())
    }
    var atMentionCap by rememberSaveable { mutableStateOf(false) }
    // Long-press targets are held by message ID, not by the message itself: the ID
    // is Saveable (so the sheet survives rotation), and resolving it against the
    // live list means an open sheet collapses on its own if its message leaves the
    // stream (author blocked, message moderated away) while it is showing.
    var actionsMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var blockTargetUid by rememberSaveable { mutableStateOf<String?>(null) }

    val activeQuery =
        if (mentionCandidates.isEmpty()) {
            null
        } else {
            DraftMentions.activeQuery(
                text = draft.text,
                cursor = draft.selection.start,
                selectionEnd = draft.selection.end,
                mentions = mentions,
            )
        }
    val suggestions =
        remember(mentionCandidates, activeQuery?.term) {
            activeQuery?.let { MentionCandidates.matching(mentionCandidates, it.term) }.orEmpty()
        }

    Column(
        // Inset for the IME *and* the system navigation bar (their union, so the
        // taller of the two wins rather than double-counting): with the keyboard
        // down this clears the message-input row above the Android nav bar
        // (back/home/recents or the gesture pill), and with the keyboard up it
        // lifts above the IME. Without the nav-bar half the input sat hidden
        // behind the system bar at the bottom of the chat.
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(KccSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (loading && messages.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (messages.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                ChannelMessageList(
                    messages = messages,
                    currentUid = currentUid,
                    mentionDisplayNames = mentionDisplayNames,
                    canLoadOlder = canLoadOlder,
                    isLoadingOlder = isLoadingOlder,
                    onLoadOlder = onLoadOlder,
                    onRetry = onRetry,
                    onViewProfile = onViewProfile,
                    onShowLocationOnMap = onShowLocationOnMap,
                    // Long-press opens the moderation sheet — never on your own
                    // message, never on one with no resolvable author, and never
                    // when the sheet would have no action to offer.
                    onMessageLongPress = { message ->
                        if (MessageModeration.canActOn(message.senderUid, currentUid) &&
                            MessageModeration.hasActions(
                                canBlock = onBlock != null,
                                reportAvailability = MessageModeration.reportAvailability(surface),
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

        // The message posted; only some mentions didn't reach anyone. That's a
        // note, not an error — hence onSurfaceVariant rather than error colour.
        if (droppedMentionCount > 0) {
            Text(
                text = stringResource(R.string.channel_mentionsNotDelivered),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(MENTION_DROPPED_TEST_TAG),
            )
        }

        if (atMentionCap) {
            Text(
                // Formatted from the constant, never spelled out in the copy: the
                // cap mirrors the backend's MAX_MESSAGE_MENTIONS, and a sentence
                // carrying its own literal would drift the moment that moves.
                text = stringResource(R.string.channel_mentionLimit, MAX_MESSAGE_MENTIONS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(MENTION_CAP_TEST_TAG),
            )
        }

        if (activeQuery != null && suggestions.isNotEmpty()) {
            MentionPicker(
                suggestions = suggestions,
                onPick = { candidate ->
                    when (
                        val result =
                            DraftMentions.insert(
                                draft = MentionDraft(draft.text, mentions),
                                query = activeQuery,
                                candidate = candidate,
                            )
                    ) {
                        is MentionInsertResult.Inserted -> {
                            mentions = result.draft.mentions
                            draft =
                                TextFieldValue(
                                    text = result.draft.text,
                                    selection = TextRange(result.cursor),
                                )
                            atMentionCap = false
                        }
                        // The one rule the server hard-rejects on, so the user
                        // gets a sentence instead of a failed send.
                        MentionInsertResult.AtCap -> atMentionCap = true
                    }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { updated ->
                    if (updated.text.length > CHANNEL_MESSAGE_MAX_LENGTH) return@OutlinedTextField
                    // Re-anchor the recorded uids against the edited text BEFORE
                    // accepting it. A mention only survives while the exact text
                    // the picker inserted still stands where it was inserted; an
                    // edit that touches (or might have touched) it drops it back
                    // to plain text rather than let the uid drift onto whatever
                    // the user typed there instead.
                    if (updated.text != draft.text) {
                        mentions =
                            DraftMentions.onTextChanged(
                                draft = MentionDraft(draft.text, mentions),
                                newText = updated.text,
                            ).mentions
                        atMentionCap = false
                        if (droppedMentionCount > 0) onDismissDroppedMentions()
                    }
                    draft = updated
                },
                placeholder = { Text(stringResource(R.string.channel_inputPlaceholder)) },
                modifier = Modifier.weight(1f).testTag(CHANNEL_INPUT_TEST_TAG),
                keyboardOptions = ChatComposerKeyboardOptions,
                singleLine = false,
            )
            Button(
                onClick = {
                    // Optimistic: hand the draft off and clear the input at once.
                    // The message appears immediately as a "sending" bubble; there
                    // is no in-flight disabled/spinner state to wait through.
                    onSend(draft.text, MentionDraft(draft.text, mentions).sendableUids)
                    draft = TextFieldValue("")
                    mentions = emptyList()
                    atMentionCap = false
                },
                enabled = ChannelThread.isSendable(draft.text),
            ) {
                Text(stringResource(R.string.channel_send))
            }
        }
    }

    // Resolve the long-pressed message against the LIVE list, so a sheet whose
    // message vanished (its author was blocked, it was moderated away) closes
    // itself rather than acting on a message that is no longer there.
    val actionsMessage = messages.firstOrNull { it.id == actionsMessageId }
    if (actionsMessage != null) {
        MessageActionsSheet(
            memberName = actionsMessage.senderDisplayName,
            canBlock = onBlock != null,
            reportAvailability = MessageModeration.reportAvailability(surface),
            onBlock = {
                actionsMessageId = null
                blockTargetUid = actionsMessage.senderUid
            },
            // Unreachable while every channel is BackendMissing (the row is
            // disabled), but wired so the callable landing only needs the route
            // to pass a submit lambda.
            onReport = { actionsMessageId = null },
            onDismiss = { actionsMessageId = null },
        )
    }

    val blockTarget = blockTargetUid
    if (blockTarget != null) {
        BlockConfirmDialog(
            memberName = messages.firstOrNull { it.senderUid == blockTarget }?.senderDisplayName,
            onConfirm = {
                blockTargetUid = null
                onBlock?.invoke(blockTarget)
            },
            onDismiss = {
                blockTargetUid = null
                onBlockDismiss()
            },
        )
    }
}

/**
 * The @-autocomplete list, shown directly above the input while a query is live.
 *
 * The surface carries its own label: it otherwise appears unannounced, and a list
 * that materialises silently over the composer is exactly what a screen-reader
 * user has no way to discover. Each row's label is merged up from its
 * display-name [Text] by the clickable, so the rows announce as themselves.
 */
@Composable
private fun MentionPicker(
    suggestions: List<MentionCandidate>,
    onPick: (MentionCandidate) -> Unit,
) {
    val pickerLabel = stringResource(R.string.channel_mentionPicker)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(KccRadius.md),
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(MENTION_PICKER_TEST_TAG)
                .semantics { contentDescription = pickerLabel },
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
            items(suggestions, key = { it.uid }) { candidate ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // Role.Button so it announces as actionable, matching
                            // the sender-header affordances on this screen.
                            .clickable(role = Role.Button) { onPick(candidate) }
                            .testTag(mentionCandidateTestTag(candidate.uid))
                            .padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s3),
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Decorative: the display name sits right next to it, and the
                    // row's clickable merges that name up as the row's own label.
                    SenderAvatar(avatarPath = candidate.avatarPath, contentDescription = null)
                    Text(
                        text = candidate.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelMessageList(
    messages: List<ChannelMessage>,
    currentUid: String,
    mentionDisplayNames: Map<String, String>,
    canLoadOlder: Boolean,
    isLoadingOlder: Boolean,
    onLoadOlder: () -> Unit,
    onRetry: (ChannelMessage) -> Unit,
    onViewProfile: ((String) -> Unit)?,
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)?,
    onMessageLongPress: (ChannelMessage) -> Unit,
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
    // bookkeeping here. Shared with the DM thread and event chat.
    val newest = messages.lastOrNull()
    KeepPinnedToNewest(
        listState = listState,
        newestMessageId = newest?.id,
        isOwnNewestMessage = newest?.senderUid == currentUid,
    )

    // Keyboard just came up: the viewport shrank from the bottom, so re-pin the
    // newest message — shared with the DM thread and event chat so the three
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
                            Text(stringResource(R.string.channel_loadOlder))
                        }
                    }
                }
            }
        }
        items(timeline, key = { it.key }) { item ->
            when (item) {
                is ChatTimelineItem.DaySeparator -> DaySeparatorRow(date = item.date, dates = dates)
                is ChatTimelineItem.Message ->
                    ChannelMessageRow(
                        message = item.message,
                        isOwn = item.message.senderUid == currentUid,
                        mentionDisplayNames = mentionDisplayNames,
                        dates = dates,
                        onViewProfile = onViewProfile,
                        onShowLocationOnMap = onShowLocationOnMap,
                        onRetry = { onRetry(item.message) },
                        onLongPress = { onMessageLongPress(item.message) },
                    )
            }
        }
    }
}

@Composable
private fun ChannelMessageRow(
    message: ChannelMessage,
    isOwn: Boolean,
    mentionDisplayNames: Map<String, String>,
    dates: ChatDateContext,
    onViewProfile: ((String) -> Unit)?,
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)?,
    onRetry: () -> Unit,
    onLongPress: () -> Unit,
) {
    if (isOwn) {
        // The time sits on the line ABOVE the bubble, aligned to the bubble's own
        // (right) edge. Your own messages carry no sender header, so this line is
        // the counterpart of the sender-name line on an incoming message — which
        // keeps the stamp in the same place on every message in the thread.
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            MessageTimeText(millis = message.createdAtMillis, dates = dates)
            // Your own bubble carries no long-press: you can neither block nor
            // report yourself.
            ChannelBubble(
                message = message,
                isOwn = true,
                mentionDisplayNames = mentionDisplayNames,
                onShowLocationOnMap = onShowLocationOnMap,
                onLongPress = null,
            )
            // Delivery status sits under your OWN optimistic bubbles only. A
            // delivered (server-sourced) message is [ChannelDeliveryState.Sent] and
            // shows nothing. Mirrors the DM thread's status line.
            OwnDeliveryStatus(message = message, onRetry = onRetry)
        }
        return
    }
    // Incoming message: avatar + sender name above the bubble (group context).
    // Tapping the sender (avatar or name) opens their read-only profile; the
    // BUBBLE's tap stays free, and its LONG-press opens the moderation sheet. A
    // malformed message can carry a blank senderUid, which would open a dead
    // profile route, so the affordance is only wired for a resolvable sender.
    val openProfile =
        onViewProfile?.takeIf { message.senderUid.isNotBlank() }?.let {
            { it(message.senderUid) }
        }
    val senderName = message.senderDisplayName ?: stringResource(R.string.channel_unknownSender)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        SenderAvatar(
            avatarPath = message.senderAvatarPath,
            // The avatar image is decorative while inert — the sender name sits
            // right next to it — but once it becomes a button it has to carry its
            // own label, or screen readers focus an unlabeled button. The label is
            // merged up from the image by the clickable below.
            contentDescription =
                if (openProfile != null) {
                    stringResource(R.string.channel_openSenderProfile, senderName)
                } else {
                    null
                },
            // Announce the sender as a button to screen readers — without the role
            // it reads as a plain image/text and the tap-to-open-profile
            // affordance is invisible to accessibility services.
            modifier =
                if (openProfile != null) {
                    Modifier.clickable(role = Role.Button, onClick = openProfile)
                } else {
                    Modifier
                },
        )
        Column {
            // Sender name and time share the header line above the bubble, the
            // conventional group-chat header. Only the NAME is the profile tap
            // target — the time is inert, so a mis-tap can't navigate.
            Row(
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        if (openProfile != null) {
                            Modifier.clickable(role = Role.Button, onClick = openProfile)
                        } else {
                            Modifier
                        },
                )
                MessageTimeText(millis = message.createdAtMillis, dates = dates)
            }
            ChannelBubble(
                message = message,
                isOwn = false,
                mentionDisplayNames = mentionDisplayNames,
                onShowLocationOnMap = onShowLocationOnMap,
                onLongPress = onLongPress,
            )
        }
    }
}

/**
 * Delivery-status line under the caller's OWN optimistic bubble: "Sending…" while
 * in flight, and a failure reason on failure — tappable to retry when the failure
 * is transient ([ChannelSendError.isRetryable]). A delivered
 * [ChannelDeliveryState.Sent] message shows nothing. Mirrors dm/ChatScreen.
 */
@Composable
private fun OwnDeliveryStatus(message: ChannelMessage, onRetry: () -> Unit) {
    when (message.deliveryState) {
        ChannelDeliveryState.Sending ->
            Text(
                text = stringResource(R.string.channel_statusSending),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        ChannelDeliveryState.Failed -> {
            val retryable = message.sendError?.isRetryable ?: true
            Text(
                // Retryable (transient) failures invite a resend; terminal ones
                // (signed out / not a member / cannot deliver) show the specific
                // reason instead of a "retry" that would just fail.
                text =
                    if (retryable) {
                        stringResource(R.string.channel_statusFailedRetry)
                    } else {
                        stringResource(message.sendError!!.messageRes())
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    if (retryable) {
                        // clickable BEFORE padding so the whole padded area is the
                        // tap target; the resend reuses the SAME idempotency key so
                        // it never double-posts.
                        Modifier.clickable(role = Role.Button, onClick = onRetry)
                            .padding(vertical = KccSpacing.s2)
                    } else {
                        Modifier
                    },
            )
        }
        ChannelDeliveryState.Sent -> Unit
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelBubble(
    message: ChannelMessage,
    isOwn: Boolean,
    mentionDisplayNames: Map<String, String>,
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    val bubbleColor =
        if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    // Highlight from the message's OWN stored mentionedUids — the set the server
    // ACCEPTED. A uid it dropped isn't in there, so its text renders plain, which
    // is exactly what it now is.
    val mentionColor =
        if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    // A shared `geo:` link renders as a single tappable chip. Coloured like a
    // mention (so it reads as "special" against the bubble) but UNDERLINED, so it
    // reads as tappable rather than merely emphasised.
    val linkColor =
        if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val linkLabel = stringResource(R.string.channel_locationLink)
    // Detection depends only on WHETHER a map-move handler exists, so the body is
    // keyed on that boolean rather than the lambda's (per-recomposition) identity —
    // otherwise every message's AnnotatedString would rebuild on each recompose.
    // The listener captures the handler that was current when the body was built;
    // its behaviour (move the map to a fixed point) is stable, so a captured-but-
    // slightly-stale handler is harmless.
    val canShowLocation = onShowLocationOnMap != null
    // Tapping an auto-linkified http/https URL opens it in the phone's DEFAULT
    // browser (an ACTION_VIEW intent the OS resolves), guarded against a missing
    // browser. The handler captures the (stable) context, so it is deliberately
    // NOT a remember key below — only the message content drives a rebuild.
    val context = LocalContext.current
    val onOpenUrl: (String) -> Unit = remember(context) { { url -> ChatUrlOpener.open(context, url) } }
    val body =
        remember(
            message.text,
            message.mentionedUids,
            mentionDisplayNames,
            mentionColor,
            linkColor,
            linkLabel,
            canShowLocation,
        ) {
            annotateMessageBody(
                text = message.text,
                mentionedUids = message.mentionedUids,
                displayNames = mentionDisplayNames,
                mentionColor = mentionColor,
                linkColor = linkColor,
                linkLabel = linkLabel,
                onShowLocationOnMap = onShowLocationOnMap,
                onOpenUrl = onOpenUrl,
            )
        }
    Surface(
        color = bubbleColor,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(KccRadius.lg),
        modifier =
            Modifier
                .widthIn(max = 280.dp)
                .then(
                    if (onLongPress != null) {
                        // combinedClickable, not pointerInput: it announces the
                        // long-press to accessibility services as a custom action,
                        // so the moderation sheet is reachable without a physical
                        // long-press. onClick is a deliberate no-op — the bubble has
                        // no tap action; only the sender header navigates.
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
}

/**
 * [text] with each accepted mention emphasised AND each shared `geo:` location
 * link turned into a single tappable chip.
 *
 * Mentions: the stored message carries uids and no offsets, so [MentionRendering]
 * maps them back onto spans by matching "@displayName" — see its KDoc for what an
 * unresolvable uid or a duplicated display name does (both are cosmetic; neither
 * affects who was notified).
 *
 * Location links: [GeoLinks.findAll] detects the `geo:lat,lng` tokens (the SAME
 * parser the clipboard writer uses, so the two can never disagree) and each is
 * replaced by a [linkLabel] chip carrying a [LinkAnnotation.Clickable]. Tapping
 * it calls [onShowLocationOnMap] — an IN-APP camera move, never an external maps
 * intent. When [onShowLocationOnMap] is null (no map to move) links are not
 * detected at all, so the raw token renders as plain text. A malformed or
 * out-of-range token is not returned by [GeoLinks.findAll], so it likewise stays
 * plain text and can never move the map to a garbage coordinate.
 *
 * Web links: [WebLinks.findAll] detects each `http://`/`https://` URL a user pasted
 * and it becomes a tappable span (the URL text itself, styled + underlined) that
 * opens the phone's default browser via [onOpenUrl]. Only http/https are ever
 * detected — dangerous schemes (`tel:`, `intent:`, `javascript:`, `file:`) never
 * become links — and nothing opens without an explicit tap.
 *
 * Mentions (`@`), location links (`geo:`) and web links (`http(s)://`) all begin
 * with a distinct prefix, so their ranges can never overlap and the three sets are
 * simply interleaved over the text in order.
 */
private fun annotateMessageBody(
    text: String,
    mentionedUids: List<String>,
    displayNames: Map<String, String>,
    mentionColor: Color,
    linkColor: Color,
    linkLabel: String,
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)?,
    onOpenUrl: (String) -> Unit,
): AnnotatedString {
    val mentionRanges = MentionRendering.highlightRanges(text, mentionedUids, displayNames)
    val geoMatches = if (onShowLocationOnMap != null) GeoLinks.findAll(text) else emptyList()
    val webMatches = WebLinks.findAll(text)
    if (mentionRanges.isEmpty() && geoMatches.isEmpty() && webMatches.isEmpty()) {
        return AnnotatedString(text)
    }

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
        while (index < text.length) {
            val geo = geoMatches.firstOrNull { it.range.first == index }
            if (geo != null) {
                val link = geo.link
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "geo",
                        styles = linkStyles,
                        linkInteractionListener = {
                            onShowLocationOnMap?.invoke(link.latitude, link.longitude)
                        },
                    ),
                ) {
                    append(linkLabel)
                }
                index = geo.range.last + 1
                continue
            }
            val web = webMatches.firstOrNull { it.range.first == index }
            if (web != null) {
                val url = web.link.url
                withLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = linkStyles,
                        linkInteractionListener = { onOpenUrl(url) },
                    ),
                ) {
                    append(url)
                }
                index = web.range.last + 1
                continue
            }
            val mention = mentionRanges.firstOrNull { it.first == index }
            if (mention != null) {
                withStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold)) {
                    append(text.substring(mention.first, mention.last + 1))
                }
                index = mention.last + 1
                continue
            }
            // Plain run up to the next mention/link boundary.
            val nextBoundary =
                (
                    geoMatches.map { it.range.first } +
                        webMatches.map { it.range.first } +
                        mentionRanges.map { it.first }
                )
                    .filter { it > index }
                    .minOrNull()
                    ?: text.length
            append(text.substring(index, nextBoundary))
            index = nextBoundary
        }
    }
}

@Composable
private fun SenderAvatar(
    avatarPath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, avatarPath)
    Box(
        // The caller's modifier (the tap-to-open-profile clickable) is applied
        // AFTER the clip, so the touch ripple is clipped to the circular avatar
        // rather than painting a square behind it.
        modifier =
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .then(modifier)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(32.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The `channel.*` send-error string for a mapped [ChannelSendError]. */
private fun ChannelSendError.messageRes(): Int =
    when (this) {
        ChannelSendError.SignedOut -> R.string.channel_sendErrorSignedOut
        ChannelSendError.NotMember -> R.string.channel_sendErrorNotMember
        ChannelSendError.Invalid -> R.string.channel_sendErrorInvalid
        ChannelSendError.CannotDeliver -> R.string.channel_sendErrorCannotDeliver
        ChannelSendError.Generic -> R.string.channel_sendErrorGeneric
    }
