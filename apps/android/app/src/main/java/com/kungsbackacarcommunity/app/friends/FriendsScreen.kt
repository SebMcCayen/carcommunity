package com.kungsbackacarcommunity.app.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * The Friends surface: add-by-nickname (with an ambiguity picker), incoming and
 * outgoing pending requests, and the established friends list. Every backend
 * error is surfaced via a `friends.*` string keyed off the mapped
 * [FriendActionError] — never a raw message.
 *
 * Direct messaging is now wired: [onOpenMessages] opens the DM inbox and each
 * friend row's "Message" button opens the 1:1 thread with that friend via
 * [onMessageFriend] (the conversation is created on the first message).
 */
@Composable
fun FriendsScreen(
    status: FriendsStatus,
    addState: AddFriendState,
    actionError: FriendActionError?,
    onSend: (String) -> Unit,
    onChooseCandidate: (String) -> Unit,
    onDismissAdd: () -> Unit,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearActionError: () -> Unit,
    onMessageFriend: (FriendSummary) -> Unit,
    onOpenMessages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nickname by remember { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<FriendSummary?>(null) }

    AeroPage(title = stringResource(R.string.shell_friendsTitle), modifier = modifier) {
        OutlinedButton(onClick = onOpenMessages, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dm_title))
        }

        AddFriendSection(
            nickname = nickname,
            onNicknameChange = {
                nickname = it
                // Clear a stale result/error as soon as the caller edits the field.
                if (addState !is AddFriendState.Idle && addState !is AddFriendState.Working) {
                    onDismissAdd()
                }
            },
            addState = addState,
            onSubmit = {
                if (nickname.isNotBlank()) onSend(nickname)
            },
            onDismissResult = {
                nickname = ""
                onDismissAdd()
            },
        )

        actionError?.let { error ->
            ErrorBanner(text = stringResource(error.messageRes()), onDismiss = onClearActionError)
        }

        when (status) {
            FriendsStatus.Loading -> CircularProgressIndicator()

            FriendsStatus.Error ->
                Text(
                    text = stringResource(R.string.friends_loadError),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

            is FriendsStatus.Loaded -> {
                if (status.incoming.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.friends_incomingTitle))
                    status.incoming.forEach { request ->
                        IncomingRequestRow(
                            request = request,
                            onAccept = { onAccept(request.requestId) },
                            onDecline = { onDecline(request.requestId) },
                        )
                    }
                }

                if (status.outgoing.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.friends_outgoingTitle))
                    status.outgoing.forEach { request -> OutgoingRequestRow(request) }
                }

                SectionHeader(stringResource(R.string.friends_listTitle))
                if (status.friends.isEmpty()) {
                    Text(
                        text = stringResource(R.string.friends_emptyFriends),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    status.friends.forEach { friend ->
                        FriendRow(
                            friend = friend,
                            onMessage = { onMessageFriend(friend) },
                            onRemove = { removeTarget = friend },
                        )
                    }
                }
            }
        }
    }

    if (addState is AddFriendState.Chooser) {
        CandidatePickerDialog(
            candidates = addState.candidates,
            onChoose = { uid ->
                nickname = ""
                onChooseCandidate(uid)
            },
            onDismiss = onDismissAdd,
        )
    }

    val target = removeTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.friends_removeConfirmTitle)) },
            text = { Text(stringResource(R.string.friends_removeConfirmBody)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(target.uid)
                        removeTarget = null
                    },
                ) {
                    Text(stringResource(R.string.friends_removeConfirmAction))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.friends_removeCancel))
                }
            },
        )
    }
}

@Composable
private fun AddFriendSection(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    addState: AddFriendState,
    onSubmit: () -> Unit,
    onDismissResult: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Text(
                text = stringResource(R.string.friends_addSectionTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = onNicknameChange,
                label = { Text(stringResource(R.string.friends_nicknameLabel)) },
                singleLine = true,
                enabled = addState !is AddFriendState.Working,
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSubmit() }),
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
            )

            when (addState) {
                is AddFriendState.Working -> CircularProgressIndicator(modifier = Modifier.size(24.dp))

                is AddFriendState.Sent -> {
                    val text =
                        if (addState.nowFriends) {
                            stringResource(R.string.friends_nowFriends)
                        } else {
                            stringResource(R.string.friends_requestSent)
                        }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                is AddFriendState.Error ->
                    Text(
                        text = stringResource(addState.error.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                else -> Unit
            }

            val isSent = addState is AddFriendState.Sent
            val isWorking = addState is AddFriendState.Working
            val buttonLabel =
                when {
                    isSent -> stringResource(R.string.friends_addDone)
                    isWorking -> stringResource(R.string.friends_addWorking)
                    else -> stringResource(R.string.friends_addAction)
                }
            Button(
                onClick = if (isSent) onDismissResult else onSubmit,
                enabled = !isWorking && (isSent || nickname.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun IncomingRequestRow(
    request: FriendRequestSummary,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            MemberHeader(user = request.otherUser)
            Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                Button(onClick = onAccept) { Text(stringResource(R.string.friends_accept)) }
                OutlinedButton(onClick = onDecline) { Text(stringResource(R.string.friends_decline)) }
            }
        }
    }
}

@Composable
private fun OutgoingRequestRow(request: FriendRequestSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MemberHeader(user = request.otherUser, modifier = Modifier.padding(end = KccSpacing.s3))
            Text(
                text = stringResource(R.string.friends_outgoingPendingLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FriendRow(
    friend: FriendSummary,
    onMessage: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            MemberHeader(
                user = FriendUser(friend.uid, friend.displayName, friend.avatarPath),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                // Opens the 1:1 DM thread with this friend; the conversation is
                // created on the first message (dm-sendMessage).
                OutlinedButton(onClick = onMessage) {
                    Text(stringResource(R.string.friends_message))
                }
                OutlinedButton(onClick = onRemove) {
                    Text(stringResource(R.string.friends_remove))
                }
            }
        }
    }
}

@Composable
private fun CandidatePickerDialog(
    candidates: List<FriendUser>,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.friends_chooseMember)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                Text(
                    text = stringResource(R.string.friends_chooseMemberBody),
                    style = MaterialTheme.typography.bodyMedium,
                )
                candidates.forEach { candidate ->
                    OutlinedButton(
                        onClick = { onChoose(candidate.uid) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MemberHeader(user = candidate, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.friends_chooseCancel)) }
        },
    )
}

@Composable
private fun MemberHeader(
    user: FriendUser,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
    ) {
        MemberAvatar(avatarPath = user.avatarPath)
        Text(
            text = user.displayName ?: stringResource(R.string.friends_unknownMember),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MemberAvatar(avatarPath: String?) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, avatarPath)
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = KccSpacing.s3),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.friends_close)) }
        }
    }
}

/** The `friends.*` string for a mapped [FriendActionError]. */
private fun FriendActionError.messageRes(): Int =
    when (this) {
        FriendActionError.SignedOut -> R.string.friends_errorSignedOut
        FriendActionError.NotMember -> R.string.friends_errorNotMember
        FriendActionError.Invalid -> R.string.friends_errorInvalid
        FriendActionError.NotFound -> R.string.friends_errorNotFound
        FriendActionError.AlreadyExists -> R.string.friends_errorAlreadyFriends
        FriendActionError.NotAddable -> R.string.friends_errorNotAddable
        FriendActionError.RequestGone -> R.string.friends_errorRequestGone
        FriendActionError.Generic -> R.string.friends_errorGeneric
    }
