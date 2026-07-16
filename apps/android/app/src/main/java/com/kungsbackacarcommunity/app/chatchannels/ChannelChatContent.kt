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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.moderation.BlockConfirmDialog
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import com.kungsbackacarcommunity.app.moderation.MessageActionsSheet
import com.kungsbackacarcommunity.app.moderation.MessageModeration

/**
 * Shared group-channel chat body (community + convoy): a message list with the
 * caller's own vs others' bubbles (others carry the sender's avatar + name, since
 * a channel has an unbounded roster), an optional "load earlier" affordance at
 * the top, and a text input + send pinned to the bottom. Stateless apart from the
 * draft, which clears only once a send succeeds. Unlike the DM [ChatScreen] this
 * is NOT wrapped in the Aero page chrome — it fills whatever container the chat
 * hub gives it (below the hub's tab row).
 *
 * @param onViewProfile opens the read-only member profile for a sender's uid.
 *   Null (the default) leaves the sender header inert — the config-less build has
 *   no profile repository to open. Only OTHER members' messages carry a sender
 *   header at all, so the caller's own messages can never navigate here.
 * @param surface which channel this is. It decides whether the long-press action
 *   sheet's "Report message" can reach a backend
 *   ([MessageModeration.reportAvailability]) — neither channel has a report
 *   callable today, so it renders disabled with an explanatory note. See
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
    sendStatus: ChannelSendStatus,
    canLoadOlder: Boolean,
    isLoadingOlder: Boolean,
    onSend: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onResetError: () -> Unit,
    modifier: Modifier = Modifier,
    onViewProfile: ((String) -> Unit)? = null,
    surface: ChatSurface = ChatSurface.CommunityChannel,
    onBlock: ((String) -> Unit)? = null,
    blockStatus: BlockActionStatus = BlockActionStatus.Idle,
    onBlockDismiss: () -> Unit = {},
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var awaitingSend by rememberSaveable { mutableStateOf(false) }
    // Long-press targets are held by message ID, not by the message itself: the ID
    // is Saveable (so the sheet survives rotation), and resolving it against the
    // live list means an open sheet collapses on its own if its message leaves the
    // stream (author blocked, message moderated away) while it is showing.
    var actionsMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var blockTargetUid by rememberSaveable { mutableStateOf<String?>(null) }

    // Clear the draft only once a send succeeds; keep it on failure.
    LaunchedEffect(sendStatus) {
        if (awaitingSend && sendStatus == ChannelSendStatus.Idle) {
            draft = ""
            awaitingSend = false
        } else if (sendStatus is ChannelSendStatus.Failed) {
            awaitingSend = false
        }
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
                    canLoadOlder = canLoadOlder,
                    isLoadingOlder = isLoadingOlder,
                    onLoadOlder = onLoadOlder,
                    onViewProfile = onViewProfile,
                    // Long-press opens the moderation sheet — never on your own
                    // message, and never on one with no resolvable author.
                    onMessageLongPress = { message ->
                        if (MessageModeration.canActOn(message.senderUid, currentUid)) {
                            actionsMessageId = message.id
                        }
                    },
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
                    if (it.length <= CHANNEL_MESSAGE_MAX_LENGTH) draft = it
                    if (sendStatus is ChannelSendStatus.Failed) onResetError()
                },
                placeholder = { Text(stringResource(R.string.channel_inputPlaceholder)) },
                modifier = Modifier.weight(1f),
                singleLine = false,
            )
            Button(
                onClick = {
                    awaitingSend = true
                    onSend(draft)
                },
                enabled = sendStatus != ChannelSendStatus.Sending && ChannelThread.isSendable(draft),
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

@Composable
private fun ChannelMessageList(
    messages: List<ChannelMessage>,
    currentUid: String,
    canLoadOlder: Boolean,
    isLoadingOlder: Boolean,
    onLoadOlder: () -> Unit,
    onViewProfile: ((String) -> Unit)?,
    onMessageLongPress: (ChannelMessage) -> Unit,
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
                onViewProfile = onViewProfile,
                onLongPress = { onMessageLongPress(message) },
            )
        }
    }
}

@Composable
private fun ChannelMessageRow(
    message: ChannelMessage,
    isOwn: Boolean,
    onViewProfile: ((String) -> Unit)?,
    onLongPress: () -> Unit,
) {
    if (isOwn) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            // Your own bubble carries no long-press: you can neither block nor
            // report yourself.
            ChannelBubble(message = message, isOwn = true, onLongPress = null)
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        SenderAvatar(
            avatarPath = message.senderAvatarPath,
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
            Text(
                text = message.senderDisplayName ?: stringResource(R.string.channel_unknownSender),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    if (openProfile != null) {
                        Modifier.clickable(role = Role.Button, onClick = openProfile)
                    } else {
                        Modifier
                    },
            )
            ChannelBubble(message = message, isOwn = false, onLongPress = onLongPress)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelBubble(message: ChannelMessage, isOwn: Boolean, onLongPress: (() -> Unit)?) {
    val bubbleColor =
        if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
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
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s3),
        )
    }
}

@Composable
private fun SenderAvatar(avatarPath: String?, modifier: Modifier = Modifier) {
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
