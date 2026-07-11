package com.kungsbackacarcommunity.app.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroLazyPage
import com.kungsbackacarcommunity.app.shell.AeroPageTitle
import com.kungsbackacarcommunity.app.shell.aeroLazyContentPadding

/**
 * In-app notification inbox (Phase 12 slice 21). Stateless: renders [state],
 * reports a tap on an unread item (→ mark read) and mark-all-read. Uses a
 * LazyColumn so only visible rows compose (the inbox is durable and can hold
 * many items).
 */
@Composable
fun NotificationsScreen(
    state: NotificationsState,
    onMarkRead: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
                            NotificationCard(item = item, onMarkRead = { onMarkRead(item.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(item: AppNotification, onMarkRead: () -> Unit) {
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
        }
    }
}
