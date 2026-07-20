package com.kungsbackacarcommunity.app.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.friends.FriendActionError
import com.kungsbackacarcommunity.app.friends.messageRes
import com.kungsbackacarcommunity.app.shell.AeroLazyPage
import com.kungsbackacarcommunity.app.shell.AeroPageTitle
import com.kungsbackacarcommunity.app.shell.aeroLazyContentPadding

/**
 * In-app notification inbox (Phase 12 slice 21). Stateless: renders [state],
 * reports a tap on an unread item (→ mark read) and mark-all-read. Uses a
 * LazyColumn so only visible rows compose (the inbox is durable and can hold
 * many items).
 *
 * FRIEND REQUESTS: a row announcing an incoming friend request answers it in
 * place, via Accept/Decline, instead of making the user find the Friends page —
 * the notification is where they were told about it, so it is where the choice
 * belongs. [pendingFriendRequestIds] maps a requester uid to their still-pending
 * request id (the live `friend-list` snapshot); a row is actionable only while
 * it resolves through that map, so a request already answered elsewhere shows no
 * buttons at all. See [Notifications.pendingFriendRequestId].
 *
 * The buttons deliberately reflect the SERVER's answer rather than guessing:
 * there is no optimistic "you are now friends" flip, because a refused accept
 * (the pair was blocked meanwhile, the request was withdrawn) would leave the
 * row asserting a friendship that does not exist. The coordinator re-fetches
 * after every response and the row re-derives from the new snapshot.
 */
@Composable
fun NotificationsScreen(
    state: NotificationsState,
    onMarkRead: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    pendingFriendRequestIds: Map<String, String> = emptyMap(),
    busyFriendRequestIds: Set<String> = emptySet(),
    friendActionError: FriendActionError? = null,
    onAcceptFriendRequest: (String) -> Unit = {},
    onDeclineFriendRequest: (String) -> Unit = {},
    onDismissFriendActionError: () -> Unit = {},
) {
    AeroLazyPage(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = aeroLazyContentPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AeroPageTitle(stringResource(R.string.notifications_title))
            }

            when (state) {
                NotificationsState.Loading ->
                    item {
                        CircularProgressIndicator()
                    }

                NotificationsState.Error ->
                    item {
                        Text(
                            text = stringResource(R.string.notifications_loadError),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                is NotificationsState.Loaded -> {
                    // A failed accept/decline — most often "that request is no
                    // longer available" because it was answered elsewhere.
                    // Dismissible, and shown above the list so it is attached to
                    // the action rather than to any one row (the row it came
                    // from has usually just lost its buttons).
                    friendActionError?.let { error ->
                        item {
                            FriendActionErrorBanner(
                                text = stringResource(error.messageRes()),
                                onDismiss = onDismissFriendActionError,
                            )
                        }
                    }
                    if (Notifications.unreadCount(state.items) > 0) {
                        item {
                            OutlinedButton(onClick = onMarkAllRead, modifier = Modifier.fillMaxWidth()) {
                                Text(text = stringResource(R.string.notifications_markAllRead))
                            }
                        }
                    }
                    if (state.items.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.notifications_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(state.items, key = { it.id }) { item ->
                            val requestId =
                                Notifications.pendingFriendRequestId(item, pendingFriendRequestIds)
                            NotificationCard(
                                item = item,
                                onMarkRead = { onMarkRead(item.id) },
                                friendRequestId = requestId,
                                friendRequestBusy = requestId != null &&
                                    requestId in busyFriendRequestIds,
                                onAcceptFriendRequest = onAcceptFriendRequest,
                                onDeclineFriendRequest = onDeclineFriendRequest,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    item: AppNotification,
    onMarkRead: () -> Unit,
    friendRequestId: String? = null,
    friendRequestBusy: Boolean = false,
    onAcceptFriendRequest: (String) -> Unit = {},
    onDeclineFriendRequest: (String) -> Unit = {},
) {
    val clickModifier = if (item.isRead) Modifier else Modifier.clickable(onClick = onMarkRead)
    Card(
        modifier = Modifier.fillMaxWidth().then(clickModifier),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (item.isRead) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(item.category.labelRes()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            (item.previewText ?: item.body)?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!item.isRead) {
                Text(
                    text = stringResource(R.string.notifications_unreadLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // Only for a friend request that is STILL pending — the caller
            // resolved it against the live snapshot, so a request answered on
            // another device simply arrives here as null and renders nothing.
            if (friendRequestId != null) {
                Row(
                    modifier = Modifier.padding(top = KccSpacing.s2),
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                ) {
                    // Disabled while this request's callable is in flight, so
                    // rapid taps can't start overlapping mutations (and can't
                    // race an accept against a decline). Mirrors the Friends
                    // page's incoming-request row.
                    Button(
                        onClick = { onAcceptFriendRequest(friendRequestId) },
                        enabled = !friendRequestBusy,
                    ) {
                        Text(stringResource(R.string.friends_accept))
                    }
                    OutlinedButton(
                        onClick = { onDeclineFriendRequest(friendRequestId) },
                        enabled = !friendRequestBusy,
                    ) {
                        Text(stringResource(R.string.friends_decline))
                    }
                }
            }
        }
    }
}

/**
 * Dismissible error for a failed accept/decline. Mirrors the Friends page's
 * ErrorBanner (same shape, same error colours) rather than importing it, which
 * is private to that screen.
 */
@Composable
private fun FriendActionErrorBanner(text: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.friends_close))
            }
        }
    }
}
