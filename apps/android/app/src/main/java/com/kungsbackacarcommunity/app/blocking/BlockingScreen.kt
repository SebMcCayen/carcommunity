package com.kungsbackacarcommunity.app.blocking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import java.text.DateFormat
import java.util.Date

/**
 * Blocked-users management screen (Phase 12 slice 8): the caller's own blocked
 * list with an unblock action (confirm dialog). Block-initiation is contextual
 * (from a chat message or map marker) and lands with those slices; this is the
 * standalone management surface.
 */
@Composable
fun BlockingScreen(
    state: BlockedUsersState,
    actionStatus: BlockActionStatus,
    onUnblock: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmTarget by remember { mutableStateOf<BlockedUser?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.profile_back))
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.blocking_blockedUsersTitle),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            if (actionStatus == BlockActionStatus.Failed) {
                item {
                    Text(
                        text = stringResource(R.string.blocking_errorGeneric),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            when (state) {
                BlockedUsersState.Loading -> item { CircularProgressIndicator() }

                BlockedUsersState.Error ->
                    item {
                        Text(
                            text = stringResource(R.string.blocking_errorGeneric),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                is BlockedUsersState.Loaded ->
                    if (state.users.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.blocking_blockedUsersEmpty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(state.users, key = { it.userId }) { user ->
                            BlockedUserRow(
                                user = user,
                                enabled = actionStatus != BlockActionStatus.Working,
                                onUnblock = { confirmTarget = user },
                            )
                        }
                    }
            }
        }
    }

    val target = confirmTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text(stringResource(R.string.blocking_unblockConfirmTitle)) },
            text = { Text(stringResource(R.string.blocking_unblockConfirmBody)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnblock(target.userId)
                        confirmTarget = null
                    },
                ) {
                    Text(stringResource(R.string.blocking_unblockConfirmAction))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) {
                    Text(stringResource(R.string.blocking_unblockCancelAction))
                }
            },
        )
    }
}

@Composable
private fun BlockedUserRow(
    user: BlockedUser,
    enabled: Boolean,
    onUnblock: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(end = 16.dp)) {
                Text(
                    text = user.displayName ?: user.userId,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val blockedAt = user.blockedAtMillis
                if (blockedAt != null) {
                    Text(
                        text = DateFormat.getDateInstance().format(Date(blockedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onUnblock, enabled = enabled) {
                Text(stringResource(R.string.blocking_unblock))
            }
        }
    }
}
