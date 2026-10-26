package com.kungsbackacarcommunity.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import com.kungsbackacarcommunity.app.design.KccButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.kungsbackacarcommunity.app.design.KccOutlinedButton as OutlinedButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Account-deletion confirmation (Phase 12 slice 25). A two-step confirm before calling the
 * soft-delete callable; on success the caller signs the user out.
 */
@Composable
fun AccountDeletionScreen(
    status: AccountDeletionStatus,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var confirming by remember { mutableStateOf(false) }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    val busy = status == AccountDeletionStatus.Deleting

    AeroPage(title = stringResource(R.string.settings_deleteAccount), modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Filled.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    text = stringResource(R.string.settings_accountDeletionWarning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_accountDeletionConfirmBody),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier =
                Modifier.fillMaxWidth().heightIn(min = KccSpacing.s12).toggleable(
                    value = acknowledged,
                    enabled = !busy,
                    role = Role.Checkbox,
                    onValueChange = { acknowledged = it },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = acknowledged, enabled = !busy, onCheckedChange = null)
            Text(
                text = stringResource(R.string.settings_accountDeletionAcknowledgement),
                modifier = Modifier.weight(1f).padding(start = KccSpacing.s3),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (status == AccountDeletionStatus.Failed) {
            Text(
                text = stringResource(R.string.settings_accountDeletionError),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = { confirming = true },
            enabled = acknowledged && !busy,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(text = stringResource(R.string.settings_deleteAccount))
        }
        OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.profile_cancelButton))
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(text = stringResource(R.string.settings_accountDeletionConfirmTitle)) },
            text = { Text(text = stringResource(R.string.settings_accountDeletionWarning)) },
            confirmButton = {
                TextButton(
                    enabled = acknowledged && !busy,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        confirming = false
                        onDelete()
                    }
                ) {
                    Text(text = stringResource(R.string.settings_deleteAccount))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(text = stringResource(R.string.profile_cancelButton))
                }
            },
        )
    }
}
