package com.kungsbackacarcommunity.app.notifications

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroPage

/** Notification settings screen: push-permission status + per-category toggles. */
@Composable
fun NotificationSettingsScreen(
    state: NotificationSettingsState,
    pushPermission: PushPermissionStatus,
    saveStatus: NotificationSettingsSaveStatus,
    onToggle: (category: String, channel: NotificationChannel, enabled: Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AeroPage(title = stringResource(R.string.notifications_settingsTitle), modifier = modifier) {
            // Runtime request: on Android 13+ an in-context prompt is far better
            // than only deep-linking to system settings. Keep a local status so
            // the card reflects the grant immediately after the dialog.
            val context = LocalContext.current
            var permissionStatus by remember(pushPermission) { mutableStateOf(pushPermission) }
            val canRequestInApp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val requestLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { permissionStatus = currentPushPermissionStatus(context) }
            PushPermissionCard(
                status = permissionStatus,
                onRequestPermission =
                    if (canRequestInApp && permissionStatus != PushPermissionStatus.GRANTED) {
                        { requestLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    } else {
                        null
                    },
                onOpenSystemSettings = onOpenSystemSettings,
            )

            Text(
                text = stringResource(R.string.notifications_settingsCategoriesTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            if (saveStatus == NotificationSettingsSaveStatus.Saving) {
                Text(
                    text = stringResource(R.string.notifications_settingsSaving),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val preferences =
                (state as? NotificationSettingsState.Loaded)?.preferences
                    ?: NotificationPreferences.ALL_ENABLED
            NotificationCategories.ACTIVE.forEach { category ->
                CategoryRow(category, preferences.effective(category), onToggle)
            }
    }
}

@Composable
private fun PushPermissionCard(
    status: PushPermissionStatus,
    onRequestPermission: (() -> Unit)?,
    onOpenSystemSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.notifications_settingsPushTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val body =
                when {
                    status == PushPermissionStatus.GRANTED ->
                        R.string.notifications_settingsPushGrantedBody
                    // An in-app prompt is available (Android 13+): we can still ask,
                    // so use the "undetermined" copy rather than telling the user to
                    // fix it in system settings.
                    onRequestPermission != null ->
                        R.string.notifications_settingsPushUndeterminedBody
                    else -> R.string.notifications_settingsPushDeniedBody
                }
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status != PushPermissionStatus.GRANTED) {
                // Prefer the in-app system prompt (Android 13+); fall back to
                // system settings, which also covers a permanent "don't ask again".
                if (onRequestPermission != null) {
                    Button(onClick = onRequestPermission) {
                        Text(text = stringResource(R.string.notifications_settingsPushAllow))
                    }
                }
                OutlinedButton(onClick = onOpenSystemSettings) {
                    Text(text = stringResource(R.string.notifications_settingsOpenSystemSettings))
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: String,
    preference: CategoryPreference,
    onToggle: (String, NotificationChannel, Boolean) -> Unit,
) {
    val essential = NotificationCategories.isEssential(category)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(categoryLabelRes(category)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ChannelToggle(
                label = stringResource(R.string.notifications_settingsInApp),
                checked = preference.inApp,
                enabled = !essential,
                onCheckedChange = { onToggle(category, NotificationChannel.IN_APP, it) },
            )
            ChannelToggle(
                label = stringResource(R.string.notifications_settingsPush),
                checked = preference.push,
                enabled = !essential,
                onCheckedChange = { onToggle(category, NotificationChannel.PUSH, it) },
            )
            if (essential) {
                Text(
                    text = stringResource(R.string.notifications_settingsEssential),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChannelToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Whole row is the toggle target with merged semantics so TalkBack reads
    // the label as the switch's accessible name (Role.Switch); the Switch's own
    // callback is null — the row owns the interaction.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

/**
 * Wire category → label. Delegates to [NotificationCategory.labelRes] so the
 * settings rows and the inbox rows can't drift apart as categories are added;
 * [NotificationCategory.fromWire] already falls back to the neutral system
 * notice for anything unrecognized.
 */
@StringRes
private fun categoryLabelRes(category: String): Int =
    NotificationCategory.fromWire(category).labelRes()
