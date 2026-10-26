package com.kungsbackacarcommunity.app.blocking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.shell.AeroLazyPage
import com.kungsbackacarcommunity.app.shell.AeroPageTitle
import com.kungsbackacarcommunity.app.shell.aeroLazyContentPadding
import java.text.DateFormat
import java.util.Date

/**
 * Blocked-users management screen (Phase 12 slice 8): the caller's own blocked list with an unblock
 * action (confirm dialog). Block-initiation is contextual (from a chat message or map marker) and
 * lands with those slices; this is the standalone management surface.
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

    AeroLazyPage(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = aeroLazyContentPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { AeroPageTitle(stringResource(R.string.blocking_blockedUsersTitle)) }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(KccSpacing.s4),
                        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                    ) {
                        Icon(
                            Icons.Filled.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(KccSpacing.s8),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                            Text(
                                stringResource(R.string.blocking_aboutTitle),
                                modifier = Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.blocking_aboutBody),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
                    enabled = actionStatus != BlockActionStatus.Working,
                    onClick = {
                        onUnblock(target.userId)
                        confirmTarget = null
                    }
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
private fun BlockedUserRow(user: BlockedUser, enabled: Boolean, onUnblock: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
        ) {
            val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The owner-only block mirror has no avatar URL. Use the native fallback.
                    Icon(
                        Icons.Filled.PersonOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(KccSpacing.s8),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user.displayName?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.blocking_unknownUser),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        user.blockedAtMillis?.let { blockedAt ->
                            Text(
                                text = stringResource(
                                    R.string.blocking_blockedOn,
                                    DateFormat.getDateInstance().format(Date(blockedAt)),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!stacked) {
                        OutlinedButton(onClick = onUnblock, enabled = enabled) {
                            Text(stringResource(R.string.blocking_unblock))
                        }
                    }
                }
                if (stacked) {
                    OutlinedButton(
                        onClick = onUnblock,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.blocking_unblock))
                    }
                }
            }
        }
    }
}
