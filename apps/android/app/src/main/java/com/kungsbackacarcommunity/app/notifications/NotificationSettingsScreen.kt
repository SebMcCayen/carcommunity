package com.kungsbackacarcommunity.app.notifications

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import com.kungsbackacarcommunity.app.design.KccButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.kungsbackacarcommunity.app.design.KccOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
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
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                permissionStatus = currentPushPermissionStatus(context)
            }
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
        if (state == NotificationSettingsState.Loading) {
            CircularProgressIndicator()
        }
        if (
            saveStatus == NotificationSettingsSaveStatus.Saved ||
                saveStatus == NotificationSettingsSaveStatus.Failed
        ) {
            Text(
                text =
                    stringResource(
                        if (saveStatus == NotificationSettingsSaveStatus.Saved)
                            R.string.privacySettings_saved
                        else R.string.privacySettings_error
                    ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color =
                    if (saveStatus == NotificationSettingsSaveStatus.Failed)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
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
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("notification_category_$category")) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            // Match the two-channel reference row when there is room. At large
            // text sizes/narrow widths, give the title and controls separate lines.
            val compact = maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.3f
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (essential) Icons.Filled.Lock else Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(categoryLabelRes(category)),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (compact) CategoryChannels(category, preference, essential, onToggle, true)
                }
                if (!compact) CategoryChannels(category, preference, essential, onToggle, false)
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
}

@Composable
private fun CategoryChannels(
    category: String,
    preference: CategoryPreference,
    essential: Boolean,
    onToggle: (String, NotificationChannel, Boolean) -> Unit,
    compact: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
        ChannelToggle(
            label = stringResource(R.string.notifications_settingsInApp),
            checked = preference.inApp,
            enabled = !essential,
            onCheckedChange = { onToggle(category, NotificationChannel.IN_APP, it) },
            modifier = (if (compact) Modifier.width(KccSpacing.s8 * 2) else Modifier.weight(1f))
                .testTag("notification_${category}_in_app"),
            compact = compact,
        )
        ChannelToggle(
            label = stringResource(R.string.notifications_settingsPush),
            checked = preference.push,
            enabled = !essential,
            onCheckedChange = { onToggle(category, NotificationChannel.PUSH, it) },
            modifier = (if (compact) Modifier.width(KccSpacing.s8 * 2) else Modifier.weight(1f))
                .testTag("notification_${category}_push"),
            compact = compact,
        )
    }
}

@Composable
private fun ChannelToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // Whole row is the toggle target with merged semantics so TalkBack reads
    // the label as the switch's accessible name (Role.Switch); the Switch's own
    // callback is null — the row owns the interaction.
    val target =
        modifier
            .heightIn(min = KccSpacing.s12)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {}
    if (compact) {
        Column(modifier = target, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Switch(checked = checked, enabled = enabled, onCheckedChange = null)
        }
        return
    }
    Row(
        modifier = target.fillMaxWidth(),
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
 * Wire category → label. Delegates to [NotificationCategory.labelRes] so the settings rows and the
 * inbox rows can't drift apart as categories are added; [NotificationCategory.fromWire] already
 * falls back to the neutral system notice for anything unrecognized.
 */
@StringRes
private fun categoryLabelRes(category: String): Int =
    NotificationCategory.fromWire(category).labelRes()
