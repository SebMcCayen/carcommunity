package com.kungsbackacarcommunity.app.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus

/**
 * Event chat (Phase 12 slice 10). Stateless apart from the message draft and
 * the report/block dialog selections. Participation is gated on [canParticipate]
 * (active member + published + going/maybe RSVP); removed messages render a
 * neutral placeholder. Reporting opens a reason picker; blocking opens a
 * confirm dialog.
 *
 * Blocking here is contextual (block a message's author). Blocks are
 * directional and never revealed to the target; the caller's own messages
 * never offer a block affordance ([EventChat.canBlock]).
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
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var awaitingSend by rememberSaveable { mutableStateOf(false) }
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
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_eventChatTitle),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            WarningCard(stringResource(R.string.chat_safeDrivingWarning))

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
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.messages, key = { it.id }) { message ->
                                MessageRow(
                                    message = message,
                                    isOwn = message.authorUserId == currentUid,
                                    // Block only another user's message, and only when blocking
                                    // is wired (canBlock). Directional; never on own messages.
                                    canBlock = canBlock && EventChat.canBlock(message, currentUid),
                                    onReport = { reportingMessageId = message.id },
                                    onBlock = { blockingUserId = message.authorUserId },
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

@Composable
private fun MessageRow(
    message: ChatMessage,
    isOwn: Boolean,
    canBlock: Boolean,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = message.authorDisplayName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.chat_unknownAuthor),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (message.isRemoved) {
                Text(
                    text = stringResource(R.string.chat_removedMessage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                )
            } else {
                Text(
                    text = message.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Moderation actions on another user's message only. You cannot
                // report or block your own message (canBlock is already false
                // for own messages via EventChat.canBlock).
                if (!isOwn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onReport) {
                            Text(text = stringResource(R.string.chat_reportMessage))
                        }
                        if (canBlock) {
                            TextButton(onClick = onBlock) {
                                Text(text = stringResource(R.string.blocking_blockUser))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.blocking_blockConfirmTitle)) },
        text = { Text(text = stringResource(R.string.blocking_blockConfirmBody)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.blocking_blockConfirmAction))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.blocking_blockCancelAction))
            }
        },
    )
}

@Composable
private fun ReportReasonDialog(onSelect: (ChatReportReason) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.chat_reportReasonPrompt)) },
        text = {
            Column {
                ReasonButton(R.string.chat_reportReasonHarassment, ChatReportReason.HARASSMENT, onSelect)
                ReasonButton(R.string.chat_reportReasonHateOrAbuse, ChatReportReason.HATE_OR_ABUSE, onSelect)
                ReasonButton(R.string.chat_reportReasonSpam, ChatReportReason.SPAM, onSelect)
                ReasonButton(R.string.chat_reportReasonUnsafeDriving, ChatReportReason.UNSAFE_DRIVING, onSelect)
                ReasonButton(R.string.chat_reportReasonPrivacy, ChatReportReason.PRIVACY, onSelect)
                ReasonButton(R.string.chat_reportReasonOther, ChatReportReason.OTHER, onSelect)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.profile_cancelButton))
            }
        },
    )
}

@Composable
private fun ReasonButton(labelRes: Int, reason: ChatReportReason, onSelect: (ChatReportReason) -> Unit) {
    TextButton(onClick = { onSelect(reason) }, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(labelRes))
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
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
