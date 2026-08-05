package com.kungsbackacarcommunity.app.friends

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.AeroLazyPage
import com.kungsbackacarcommunity.app.shell.AeroPageTitle
import com.kungsbackacarcommunity.app.shell.aeroLazyContentPadding

/**
 * The Friends surface: add-by-nickname (with an ambiguity picker), incoming and
 * outgoing pending requests, and the established friends list. Every backend
 * error is surfaced via a `friends.*` string keyed off the mapped
 * [FriendActionError] — never a raw message.
 *
 * Each friend row's "Message" button opens the 1:1 DM thread with that friend
 * via [onMessageFriend] (the conversation is created on the first message).
 *
 * The established list is sorted client-side by [friendSort]; the control lives
 * above the list ([FriendSortChips]) and never triggers a backend round-trip —
 * every friend row already carries its `friendsSince` timestamp.
 */
@Composable
fun FriendsScreen(
    status: FriendsStatus,
    addState: AddFriendState,
    actionError: FriendActionError?,
    busyRows: Set<String>,
    onSend: (String) -> Unit,
    onChooseCandidate: (String) -> Unit,
    onDismissAdd: () -> Unit,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearActionError: () -> Unit,
    onMessageFriend: (FriendSummary) -> Unit,
    onViewProfile: (FriendSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    var nickname by remember { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<FriendSummary?>(null) }
    // Client-side ordering of the established list. rememberSaveable (matching the
    // History list's sort in DrivesScreen) so the choice survives config changes
    // and recomposition for the session. Defaults to EARLIEST_ADDED, which is the
    // order friend-list already returns, so the list never reorders on first load.
    var friendSort by rememberSaveable { mutableStateOf(FriendSort.EARLIEST_ADDED) }

    // Durable list: a LazyColumn so only visible rows compose. Static sections
    // (title, add-friend, headers, banners) are `item {}` blocks; the request and
    // friend rows are keyed so recomposition/scroll state is stable.
    AeroLazyPage(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = aeroLazyContentPadding(),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s4),
        ) {
            item(key = "title") {
                AeroPageTitle(stringResource(R.string.shell_friendsTitle))
            }

            item(key = "add-friend") {
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
            }

            actionError?.let { error ->
                item(key = "action-error") {
                    ErrorBanner(
                        text = stringResource(error.messageRes()),
                        onDismiss = onClearActionError,
                    )
                }
            }

            when (status) {
                FriendsStatus.Loading -> item(key = "loading") { CircularProgressIndicator() }

                // A load failure (including the member gate) is surfaced as a
                // friendly, neutral info notice — no red error styling and no
                // retry button; the page just shows add-friend plus this note.
                is FriendsStatus.Error ->
                    item(key = "load-error") {
                        InfoNoticeCard(text = stringResource(status.error.messageRes()))
                    }

                is FriendsStatus.Loaded -> {
                    if (status.incoming.isNotEmpty()) {
                        item(key = "incoming-header") {
                            SectionHeader(stringResource(R.string.friends_incomingTitle))
                        }
                        items(status.incoming, key = { "incoming-${it.requestId}" }) { request ->
                            IncomingRequestRow(
                                request = request,
                                working = request.requestId in busyRows,
                                onAccept = { onAccept(request.requestId) },
                                onDecline = { onDecline(request.requestId) },
                            )
                        }
                    }

                    if (status.outgoing.isNotEmpty()) {
                        item(key = "outgoing-header") {
                            SectionHeader(stringResource(R.string.friends_outgoingTitle))
                        }
                        items(status.outgoing, key = { "outgoing-${it.requestId}" }) { request ->
                            OutgoingRequestRow(
                                request = request,
                                // The cancel row is keyed in the coordinator by the
                                // recipient uid (cancelRequest is addressed by
                                // RECIPIENT), so its in-flight guard is too.
                                working = request.toUid in busyRows,
                                onCancel = { onCancel(request.toUid) },
                            )
                        }
                    }

                    item(key = "friends-header") {
                        SectionHeader(stringResource(R.string.friends_listTitle))
                    }
                    if (status.friends.isEmpty()) {
                        item(key = "friends-empty") {
                            Text(
                                text = stringResource(R.string.friends_emptyFriends),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        // The sort control only earns its space once there is more
                        // than one friend to order.
                        if (status.friends.size > 1) {
                            item(key = "friends-sort") {
                                FriendSortChips(
                                    selected = friendSort,
                                    onSelect = { friendSort = it },
                                )
                            }
                        }
                        // Pure, client-side ordering over the already-loaded list —
                        // no backend round-trip (every row carries `friendsSince`).
                        val sortedFriends = sortFriends(status.friends, friendSort)
                        items(sortedFriends, key = { "friend-${it.uid}" }) { friend ->
                            FriendRow(
                                friend = friend,
                                working = friend.uid in busyRows,
                                onViewProfile = { onViewProfile(friend) },
                                onMessage = { onMessageFriend(friend) },
                                onRemove = { removeTarget = friend },
                            )
                        }
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
        // Falls back to the neutral "Member" label when the friend has no
        // display name, so the unfriend prompt never reads "Unfriend ?".
        val targetName = target.displayName?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.friends_unknownMember)
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.friends_removeConfirmTitle, targetName)) },
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
                is AddFriendState.Working -> CircularProgressIndicator(modifier = Modifier.size(KccSpacing.s6))

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
    working: Boolean,
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
                // Disabled while this row's accept/decline callable is in flight,
                // so rapid taps can't start overlapping mutations.
                Button(onClick = onAccept, enabled = !working) {
                    Text(stringResource(R.string.friends_accept))
                }
                OutlinedButton(onClick = onDecline, enabled = !working) {
                    Text(stringResource(R.string.friends_decline))
                }
            }
        }
    }
}

@Composable
private fun OutgoingRequestRow(
    request: FriendRequestSummary,
    working: Boolean,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MemberHeader(
                    user = request.otherUser,
                    modifier = Modifier.padding(end = KccSpacing.s3),
                )
                Text(
                    text = stringResource(R.string.friends_outgoingPendingLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Withdraws a request sent by mistake. Disabled while the cancel
            // callable is in flight so rapid taps can't overlap.
            OutlinedButton(onClick = onCancel, enabled = !working) {
                Text(stringResource(R.string.friends_cancelRequestAction))
            }
        }
    }
}

@Composable
private fun FriendRow(
    friend: FriendSummary,
    working: Boolean,
    onViewProfile: () -> Unit,
    onMessage: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            // Tapping the member (avatar + name) opens their read-only profile.
            MemberHeader(
                user = FriendUser(friend.uid, friend.displayName, friend.avatarPath),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // Announce the row as a button to screen readers — without
                        // this it reads as plain text and its tap-to-open-profile
                        // affordance is invisible to accessibility services.
                        .clickable(role = Role.Button, onClick = onViewProfile),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                // Opens the 1:1 DM thread with this friend; the conversation is
                // created on the first message (dm-sendMessage).
                OutlinedButton(onClick = onMessage) {
                    Text(stringResource(R.string.friends_message))
                }
                // Disabled while this friend's removal callable is in flight.
                OutlinedButton(onClick = onRemove, enabled = !working) {
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
                .size(KccSpacing.s10)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(KccSpacing.s10),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KccSpacing.s6),
            )
        }
    }
}

/**
 * A soft, neutral notice used for the load-error / member-gate state: an info
 * icon plus muted text, styled to sit calmly within the Aero theme rather than
 * shouting in the error colour.
 */
@Composable
private fun InfoNoticeCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KccSpacing.s6),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The friends-list sort control: a label plus a single-select row of
 * [FilterChip]s, one per [FriendSort]. Horizontally scrollable so the chips
 * never wrap or clip on a narrow screen. Exactly one chip is always selected.
 */
@Composable
private fun FriendSortChips(
    selected: FriendSort,
    onSelect: (FriendSort) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
        SectionHeader(stringResource(R.string.friends_sortLabel))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            FriendSort.entries.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(stringResource(option.labelRes())) },
                )
            }
        }
    }
}

@StringRes
private fun FriendSort.labelRes(): Int =
    when (this) {
        FriendSort.NAME -> R.string.friends_sortAlphabetical
        FriendSort.RECENTLY_ADDED -> R.string.friends_sortRecentlyAdded
        FriendSort.EARLIEST_ADDED -> R.string.friends_sortEarliestAdded
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

/**
 * The `friends.*` string for a mapped [FriendActionError].
 *
 * `internal` rather than private because the convoy invite picker renders the
 * SAME failures from the same shared [FriendsCoordinator] and must not
 * re-describe them. It previously carried its own single flat "your friends
 * couldn't be loaded right now" notice for every [FriendsStatus.Error], which
 * is how a backend outage, being signed out and a dropped connection all read
 * identically there.
 */
@StringRes
internal fun FriendActionError.messageRes(): Int =
    when (this) {
        FriendActionError.SignedOut -> R.string.friends_errorSignedOut
        FriendActionError.NotMember -> R.string.friends_errorNotMember
        FriendActionError.Invalid -> R.string.friends_errorInvalid
        FriendActionError.SelfRequest -> R.string.friends_errorSelfRequest
        FriendActionError.NotFound -> R.string.friends_errorNotFound
        FriendActionError.AlreadyFriends -> R.string.friends_errorAlreadyFriends
        FriendActionError.RequestAlreadySent -> R.string.friends_errorRequestAlreadySent
        FriendActionError.NotAddable -> R.string.friends_errorNotAddable
        FriendActionError.RequestGone -> R.string.friends_errorRequestGone
        FriendActionError.Network -> R.string.friends_errorNetwork
        FriendActionError.TemporarilyUnavailable -> R.string.friends_errorTemporarilyUnavailable
        FriendActionError.Generic -> R.string.friends_errorGeneric
    }
