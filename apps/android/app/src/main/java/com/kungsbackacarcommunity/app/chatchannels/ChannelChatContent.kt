package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl

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
 * ASCII unit separator: it cannot occur in a uid, and the composer's own input
 * never produces one, so it is a safe delimiter for [MentionSpansSaver].
 */
private const val MENTION_SEPARATOR = '\u001F'

/**
 * Saves the draft's mention spans across configuration change / process death,
 * alongside the draft text itself. Flattened to strings because a
 * [rememberSaveable] Bundle takes no arbitrary data classes. A row that doesn't
 * round-trip is dropped — that costs a mention rather than restoring a
 * half-parsed one, and the composer re-verifies every restored span against the
 * text on the next edit regardless.
 */
internal val MentionSpansSaver: Saver<List<MentionSpan>, Any> =
    Saver(
        save = { spans ->
            ArrayList(
                spans.map { span ->
                    listOf(span.uid, span.label, span.start.toString())
                        .joinToString(MENTION_SEPARATOR.toString())
                },
            )
        },
        restore = { saved ->
            @Suppress("UNCHECKED_CAST")
            (saved as? List<String>)?.mapNotNull { encoded ->
                val parts = encoded.split(MENTION_SEPARATOR)
                val start = parts.getOrNull(2)?.toIntOrNull()
                if (parts.size == 3 && start != null) {
                    MentionSpan(uid = parts[0], label = parts[1], start = start)
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
 * draft, which clears only once a send succeeds. Unlike the DM [ChatScreen] this
 * is NOT wrapped in the Aero page chrome — it fills whatever container the chat
 * hub gives it (below the hub's tab row).
 *
 * @MENTIONS are COMMUNITY-only, switched on by a non-empty [mentionCandidates].
 * Convoy passes none and gets no picker: per the backend, convoyChat-post accepts
 * no mentions and every convoy message stores `mentionedUids: []`, so there is
 * nothing there to pick or to highlight.
 *
 * @param mentionCandidates members the @-picker may insert (already excludes the
 *   caller). Empty disables the picker entirely.
 * @param mentionDisplayNames uid → display name for HIGHLIGHTING. Deliberately a
 *   superset of the candidates: it keeps the caller's own name, since being
 *   mentioned yourself must highlight too.
 * @param droppedMentionCount mentions the server dropped from the last send (see
 *   [ChannelChatCoordinator.droppedMentionCount]); > 0 shows one quiet note.
 */
@Composable
fun ChannelChatContent(
    messages: List<ChannelMessage>,
    currentUid: String,
    loading: Boolean,
    emptyText: String,
    sendStatus: ChannelSendStatus,
    canLoadOlder: Boolean,
    isLoadingOlder: Boolean,
    onSend: (String, List<String>) -> Unit,
    onLoadOlder: () -> Unit,
    onResetError: () -> Unit,
    modifier: Modifier = Modifier,
    mentionCandidates: List<MentionCandidate> = emptyList(),
    mentionDisplayNames: Map<String, String> = emptyMap(),
    droppedMentionCount: Int = 0,
    onDismissDroppedMentions: () -> Unit = {},
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
    var awaitingSend by rememberSaveable { mutableStateOf(false) }
    var atMentionCap by rememberSaveable { mutableStateOf(false) }

    // Clear the draft only once a send succeeds; keep it on failure.
    LaunchedEffect(sendStatus) {
        if (awaitingSend && sendStatus == ChannelSendStatus.Idle) {
            draft = TextFieldValue("")
            mentions = emptyList()
            atMentionCap = false
            awaitingSend = false
        } else if (sendStatus is ChannelSendStatus.Failed) {
            awaitingSend = false
        }
    }

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
                )
            }
        }

        if (sendStatus is ChannelSendStatus.Failed) {
            Text(
                text = stringResource(sendStatus.error.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
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
                text = stringResource(R.string.channel_mentionLimit),
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
                    if (sendStatus is ChannelSendStatus.Failed) onResetError()
                },
                placeholder = { Text(stringResource(R.string.channel_inputPlaceholder)) },
                modifier = Modifier.weight(1f).testTag(CHANNEL_INPUT_TEST_TAG),
                singleLine = false,
            )
            Button(
                onClick = {
                    awaitingSend = true
                    onSend(draft.text, MentionDraft(draft.text, mentions).sendableUids)
                },
                enabled = sendStatus != ChannelSendStatus.Sending &&
                    ChannelThread.isSendable(draft.text),
            ) {
                Text(stringResource(R.string.channel_send))
            }
        }
    }
}

/** The @-autocomplete list, shown directly above the input while a query is live. */
@Composable
private fun MentionPicker(
    suggestions: List<MentionCandidate>,
    onPick: (MentionCandidate) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(KccRadius.md),
        modifier = Modifier.fillMaxWidth().testTag(MENTION_PICKER_TEST_TAG),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
            items(suggestions, key = { it.uid }) { candidate ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(candidate) }
                            .testTag(mentionCandidateTestTag(candidate.uid))
                            .padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s3),
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SenderAvatar(avatarPath = candidate.avatarPath)
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
) {
    val listState = rememberLazyListState()
    // The optional "load older" row is a single item prepended before the
    // messages, so every message's LazyColumn index is shifted by +1 while it is
    // present. Track that offset so the auto-scroll targets the real last item.
    val headerOffset = if (canLoadOlder || isLoadingOlder) 1 else 0
    // Auto-scroll to the newest message only when it won't fight the reader:
    // the message is the user's own send, or they are already near the bottom.
    LaunchedEffect(messages.lastOrNull()?.id) {
        val newest = messages.lastOrNull() ?: return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val totalItems = layoutInfo.totalItemsCount
        val nearBottom = totalItems == 0 || lastVisibleIndex >= totalItems - 2
        val isOwnSend = newest.senderUid == currentUid
        if (isOwnSend || nearBottom) {
            // Index into the LazyColumn, not the messages list: account for the
            // header so we land on the newest message rather than the one before.
            listState.animateScrollToItem(messages.lastIndex + headerOffset)
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
                            Text(stringResource(R.string.channel_loadOlder))
                        }
                    }
                }
            }
        }
        items(messages, key = { it.id }) { message ->
            ChannelMessageRow(
                message = message,
                isOwn = message.senderUid == currentUid,
                mentionDisplayNames = mentionDisplayNames,
            )
        }
    }
}

@Composable
private fun ChannelMessageRow(
    message: ChannelMessage,
    isOwn: Boolean,
    mentionDisplayNames: Map<String, String>,
) {
    if (isOwn) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            ChannelBubble(message = message, isOwn = true, mentionDisplayNames = mentionDisplayNames)
        }
        return
    }
    // Incoming message: avatar + sender name above the bubble (group context).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        SenderAvatar(avatarPath = message.senderAvatarPath)
        Column {
            Text(
                text = message.senderDisplayName ?: stringResource(R.string.channel_unknownSender),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChannelBubble(
                message = message,
                isOwn = false,
                mentionDisplayNames = mentionDisplayNames,
            )
        }
    }
}

@Composable
private fun ChannelBubble(
    message: ChannelMessage,
    isOwn: Boolean,
    mentionDisplayNames: Map<String, String>,
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
    val body =
        remember(message.text, message.mentionedUids, mentionDisplayNames, mentionColor) {
            annotateMentions(
                text = message.text,
                mentionedUids = message.mentionedUids,
                displayNames = mentionDisplayNames,
                mentionColor = mentionColor,
            )
        }
    Surface(
        color = bubbleColor,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(KccRadius.lg),
        modifier = Modifier.widthIn(max = 280.dp),
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
 * [text] with each accepted mention emphasised. The stored message carries uids
 * and no offsets, so [MentionRendering] maps them back onto spans by matching
 * "@displayName" — see its KDoc for what an unresolvable uid or a duplicated
 * display name does (both are cosmetic; neither affects who was notified).
 */
private fun annotateMentions(
    text: String,
    mentionedUids: List<String>,
    displayNames: Map<String, String>,
    mentionColor: Color,
): AnnotatedString {
    val ranges = MentionRendering.highlightRanges(text, mentionedUids, displayNames)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var index = 0
        for (range in ranges) {
            if (range.first > index) append(text.substring(index, range.first))
            withStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(range.first, range.last + 1))
            }
            index = range.last + 1
        }
        if (index < text.length) append(text.substring(index))
    }
}

@Composable
private fun SenderAvatar(avatarPath: String?) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, avatarPath)
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(32.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
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
