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
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl

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
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var awaitingSend by rememberSaveable { mutableStateOf(false) }

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
}

@Composable
private fun ChannelMessageList(
    messages: List<ChannelMessage>,
    currentUid: String,
    canLoadOlder: Boolean,
    isLoadingOlder: Boolean,
    onLoadOlder: () -> Unit,
    onViewProfile: ((String) -> Unit)?,
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
            )
        }
    }
}

@Composable
private fun ChannelMessageRow(
    message: ChannelMessage,
    isOwn: Boolean,
    onViewProfile: ((String) -> Unit)?,
) {
    if (isOwn) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            ChannelBubble(message = message, isOwn = true)
        }
        return
    }
    // Incoming message: avatar + sender name above the bubble (group context).
    // Tapping the sender (avatar or name — never the bubble, which stays free for
    // message-level actions) opens their read-only profile. A malformed message
    // can carry a blank senderUid, which would open a dead profile route, so the
    // affordance is only wired for a resolvable sender.
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
            ChannelBubble(message = message, isOwn = false)
        }
    }
}

@Composable
private fun ChannelBubble(message: ChannelMessage, isOwn: Boolean) {
    val bubbleColor =
        if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bubbleColor,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(KccRadius.lg),
        modifier = Modifier.widthIn(max = 280.dp),
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
