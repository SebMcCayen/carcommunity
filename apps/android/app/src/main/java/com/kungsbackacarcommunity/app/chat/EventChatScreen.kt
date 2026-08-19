package com.kungsbackacarcommunity.app.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.design.ChatComposerKeyboardOptions
import com.kungsbackacarcommunity.app.moderation.BlockConfirmDialog
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import com.kungsbackacarcommunity.app.moderation.MessageActionsSheet
import com.kungsbackacarcommunity.app.moderation.MessageModeration
import com.kungsbackacarcommunity.app.moderation.ReportReasonDialog

/**
 * Event chat (Phase 12 slice 10). Stateless apart from the message draft and
 * the report/block sheet selections. Participation is gated on [canParticipate]
 * (active member + published + going/maybe RSVP); removed messages render a
 * neutral placeholder.
 *
 * Moderation actions now live behind a LONG-PRESS on another member's message,
 * opening the shared [MessageActionsSheet] — the same gesture and the same sheet
 * as the community/convoy channels and DMs, replacing the inline Report/Block
 * text buttons that used to sit under every incoming message. From there,
 * reporting opens the reason picker (event chat is the one surface with a report
 * callable, `events-reportChatMessage`) and blocking opens a confirm dialog.
 *
 * Blocking here is contextual (block a message's author). Blocks are
 * directional and never revealed to the target; the caller's own messages
 * never offer a block affordance ([EventChat.canBlock]).
 *
 * [onViewProfile] opens an author's read-only member profile from their name;
 * as in the group channels the caller's OWN messages never navigate, and null
 * (config-less build) leaves every name inert.
 */
@Composable
fun EventChatScreen(
    state: ChatMessagesState,
    currentUid: String,
    canParticipate: Boolean,
    sendStatus: ChatSendStatus,
    reportStatus: ChatReportStatus,
    onSend: (String) -> Unit,
    onReport: (String, ChatReportReason) -> Unit,
    onReportDismiss: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Null when blocking is unavailable (config-less builds): no block
    // affordance and no block-status surfacing.
    canBlock: Boolean = false,
    blockStatus: BlockActionStatus = BlockActionStatus.Idle,
    onBlock: (String) -> Unit = {},
    onBlockDismiss: () -> Unit = {},
    onViewProfile: ((String) -> Unit)? = null,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var awaitingSend by rememberSaveable { mutableStateOf(false) }
    // The long-pressed message, held by ID: Saveable (the sheet survives rotation)
    // and resolved against the live list, so a sheet whose message left the stream
    // (author blocked, message moderated away) closes itself.
    var actionsMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var reportingMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var blockingUserId by rememberSaveable { mutableStateOf<String?>(null) }

    // Clear the draft only once a post actually succeeds; keep it on failure.
    LaunchedEffect(sendStatus) {
        if (awaitingSend && sendStatus == ChatSendStatus.Idle) {
            draft = ""
            awaitingSend = false
        } else if (sendStatus == ChatSendStatus.Failed) {
            awaitingSend = false
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            // TOP: the status-bar inset, via the SAME statusBarsPadding() the shared
            // AeroPage chrome applies, so the title clears the clock/notification
            // icons like every other top-level screen — it used to sit jammed under
            // the status bar because this screen self-scaffolds (raw Surface) instead
            // of going through AeroPage.
            // BOTTOM: the IME *and* the navigation-bar inset (their union, so the
            // taller of the two wins rather than double-counting), matching the group
            // channels' and DM composers. Event chat had NO inset handling at all:
            // its composer sat under the nav bar with the keyboard down and behind
            // the keyboard with it up.
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_eventChatTitle),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )

            if (!canParticipate) {
                InfoCard(
                    title = stringResource(R.string.chat_memberRequired),
                    body = stringResource(R.string.chat_rsvpRequired),
                )
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.profile_back))
                }
                return@Column
            }

            when (state) {
                ChatMessagesState.Loading ->
                    Text(
                        text = stringResource(R.string.chat_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                ChatMessagesState.Error ->
                    Text(
                        text = stringResource(R.string.chat_errorLoading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                is ChatMessagesState.Loaded ->
                    if (state.messages.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        val listState = rememberLazyListState()
                        // On open, JUMP straight to the newest message (not the top
                        // of history), and thereafter follow new messages down to the
                        // bottom — but only when it won't fight a reader who scrolled
                        // up. Shared with the DM thread and group channels so the
                        // three surfaces cannot drift; see [KeepPinnedToNewest] for
                        // the open-jump vs new-message-follow split.
                        val newest = state.messages.lastOrNull()
                        KeepPinnedToNewest(
                            listState = listState,
                            newestMessageId = newest?.id,
                            isOwnNewestMessage = newest?.authorUserId == currentUid,
                        )
                        // Keyboard just came up: the screen's ime padding shrank
                        // this list's viewport from the bottom and a LazyColumn
                        // holds its scroll OFFSET, not its bottom edge, so the
                        // newest message would slide under the composer. Same
                        // shared rising-edge re-pin as the group channels and DM
                        // threads — and, as there, only for a reader already at
                        // the bottom, so tapping the input while reading history
                        // doesn't yank them down.
                        RepinToNewestOnImeRise(listState)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.messages, key = { it.id }) { message ->
                                val isOwn = message.authorUserId == currentUid
                                MessageRow(
                                    message = message,
                                    // Tap the author's name → their profile. Never
                                    // for your own message, and never for a blank
                                    // author uid (a dead profile route).
                                    onViewProfile =
                                        onViewProfile
                                            ?.takeIf { !isOwn && message.authorUserId.isNotBlank() }
                                            ?.let { { it(message.authorUserId) } },
                                    // Long-press → the moderation sheet. Never on
                                    // your own message, nor on one with no
                                    // resolvable author. A REMOVED message carries
                                    // no body to report and already shows a neutral
                                    // placeholder, so it stays inert too — as does an
                                    // AUTO_HIDDEN one (collapsed, already reported).
                                    onLongPress =
                                        if (!message.isRemoved &&
                                            !message.isAutoHidden &&
                                            MessageModeration.canActOn(message.authorUserId, currentUid) &&
                                            MessageModeration.hasActions(
                                                canBlock = canBlock,
                                                reportAvailability =
                                                    MessageModeration.reportAvailability(
                                                        ChatSurface.EventChat,
                                                    ),
                                            )
                                        ) {
                                            { actionsMessageId = message.id }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    }
            }

            if (sendStatus == ChatSendStatus.Failed) {
                Text(
                    text = stringResource(R.string.chat_errorSending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (reportStatus == ChatReportStatus.Failed) {
                Text(
                    text = stringResource(R.string.chat_reportError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (reportStatus == ChatReportStatus.Done) {
                Text(
                    text = stringResource(R.string.chat_reportSubmitted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= EventChat.MESSAGE_MAX_LENGTH) draft = it },
                    placeholder = { Text(text = stringResource(R.string.chat_inputPlaceholder)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = ChatComposerKeyboardOptions,
                    singleLine = false,
                )
                Button(
                    onClick = {
                        awaitingSend = true
                        onSend(draft)
                    },
                    enabled = sendStatus != ChatSendStatus.Sending && EventChat.isSendable(draft),
                ) {
                    Text(text = stringResource(R.string.chat_sendButton))
                }
            }
        }
    }

    // Resolve the long-pressed message against the live list, so a sheet whose
    // message vanished closes rather than acting on a message that is gone.
    val actionsMessage =
        (state as? ChatMessagesState.Loaded)?.messages?.firstOrNull { it.id == actionsMessageId }
    if (actionsMessage != null) {
        MessageActionsSheet(
            memberName = actionsMessage.authorDisplayName,
            // Directional; never on own messages (already excluded from the
            // long-press) and only when blocking is wired.
            canBlock = canBlock && EventChat.canBlock(actionsMessage, currentUid),
            // Event chat is the ONE surface with a report callable, so the report
            // row is enabled here and opens the real reason picker.
            reportAvailability = MessageModeration.reportAvailability(ChatSurface.EventChat),
            onBlock = {
                actionsMessageId = null
                blockingUserId = actionsMessage.authorUserId
            },
            onReport = {
                actionsMessageId = null
                reportingMessageId = actionsMessage.id
            },
            onDismiss = { actionsMessageId = null },
        )
    }

    val selectedMessageId = reportingMessageId
    if (selectedMessageId != null) {
        ReportReasonDialog(
            onSelect = { reason ->
                reportingMessageId = null
                onReport(selectedMessageId, reason)
            },
            onDismiss = {
                reportingMessageId = null
                onReportDismiss()
            },
        )
    }

    val selectedBlockUserId = blockingUserId
    if (selectedBlockUserId != null) {
        BlockConfirmDialog(
            memberName =
                (state as? ChatMessagesState.Loaded)
                    ?.messages
                    ?.firstOrNull { it.authorUserId == selectedBlockUserId }
                    ?.authorDisplayName,
            onConfirm = {
                blockingUserId = null
                onBlock(selectedBlockUserId)
            },
            onDismiss = {
                blockingUserId = null
                onBlockDismiss()
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: ChatMessage,
    onViewProfile: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    // Per-user, ephemeral reveal for an auto-hidden message. Keyed on the message
    // id so a reveal never leaks onto a different message reusing this row, and
    // rememberSaveable so it survives rotation without being persisted anywhere.
    var revealed by rememberSaveable(message.id) { mutableStateOf(false) }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (onLongPress != null) {
                        // combinedClickable, not pointerInput: it announces the
                        // long-press to accessibility services as a custom action,
                        // so the moderation sheet is reachable without a physical
                        // long-press. onClick is a deliberate no-op — only the
                        // author's name navigates.
                        Modifier.combinedClickable(onLongClick = onLongPress, onClick = {})
                    } else {
                        Modifier
                    },
                ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = message.authorDisplayName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.chat_unknownAuthor),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                // Tapping the author opens their read-only profile. Announced as a
                // button so the affordance reaches accessibility services.
                modifier =
                    if (onViewProfile != null) {
                        Modifier.clickable(role = Role.Button, onClick = onViewProfile)
                    } else {
                        Modifier
                    },
            )
            when {
                message.isRemoved ->
                    Text(
                        text = stringResource(R.string.chat_removedMessage),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )

                message.isAutoHidden && !revealed -> {
                    // Auto-hidden after several reports: collapsed placeholder + a
                    // reveal control. Revealing is LOCAL and ephemeral — it only
                    // expands this row in this reader's UI; nothing is written and
                    // the message stays hidden for everyone else.
                    Text(
                        text = stringResource(R.string.chat_reportedHidden),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                    TextButton(
                        onClick = { revealed = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(text = stringResource(R.string.chat_showReportedMessage))
                    }
                }

                else -> {
                    // Auto-linkify any http/https URL the author pasted so it opens in
                    // the phone's default browser on tap. Detection is content-only, so
                    // the AnnotatedString is keyed on the message text (the context-
                    // capturing opener is deliberately not a key).
                    val context = LocalContext.current
                    val linkColor = MaterialTheme.colorScheme.primary
                    val body =
                        remember(message.message, linkColor) {
                            annotateWebLinks(message.message, linkColor) { url ->
                                ChatUrlOpener.open(context, url)
                            }
                        }
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * [text] with each pasted `http://`/`https://` URL turned into a tappable link (the
 * URL text itself, styled + underlined) that opens the phone's default browser via
 * [onOpenUrl]. Only http/https are linkified (see [WebLinks]); the rest of the
 * message stays plain. Returns a plain [AnnotatedString] when there is no URL.
 */
private fun annotateWebLinks(
    text: String,
    linkColor: Color,
    onOpenUrl: (String) -> Unit,
): AnnotatedString {
    val webMatches = WebLinks.findAll(text)
    if (webMatches.isEmpty()) return AnnotatedString(text)

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
        for (match in webMatches) {
            if (match.range.first > index) append(text.substring(index, match.range.first))
            val url = match.link.url
            withLink(
                LinkAnnotation.Url(
                    url = url,
                    styles = linkStyles,
                    linkInteractionListener = { onOpenUrl(url) },
                ),
            ) {
                append(url)
            }
            index = match.range.last + 1
        }
        if (index < text.length) append(text.substring(index))
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
