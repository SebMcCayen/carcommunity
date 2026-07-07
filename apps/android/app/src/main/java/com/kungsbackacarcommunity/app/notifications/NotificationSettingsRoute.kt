package com.kungsbackacarcommunity.app.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Notification settings integration route: observe the owner's preferences,
 * toggle a channel (recompute + save via the coordinator), and surface the
 * push-permission status. Permission status + the system-settings deep link
 * are supplied by the caller so this stays Firebase-/Android-free and testable.
 */
@Composable
fun NotificationSettingsRoute(
    repository: NotificationSettingsRepository,
    coordinator: NotificationSettingsCoordinator?,
    uid: String,
    pushPermission: PushPermissionStatus,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by
        remember(repository, uid) { repository.observePreferences(uid) }
            .collectAsState(initial = NotificationSettingsState.Loading)
    val saveStatus by
        (coordinator?.saveStatus ?: flowOf(NotificationSettingsSaveStatus.Idle))
            .collectAsState(initial = NotificationSettingsSaveStatus.Idle)

    NotificationSettingsScreen(
        state = state,
        pushPermission = pushPermission,
        saveStatus = saveStatus,
        onToggle = { category, channel, enabled ->
            // Only act on a loaded snapshot and when no save is in flight —
            // otherwise a toggle during Loading would persist ALL_ENABLED over
            // the user's real preferences, and a toggle during Saving is a
            // no-op the coordinator would drop anyway.
            val loaded = state as? NotificationSettingsState.Loaded
            if (loaded != null && saveStatus != NotificationSettingsSaveStatus.Saving) {
                val updated = loaded.preferences.withToggle(category, channel, enabled)
                coordinator?.let { c -> scope.launch { c.save(uid, updated) } }
            }
        },
        onOpenSystemSettings = onOpenSystemSettings,
        onBack = {
            coordinator?.reset()
            onBack()
        },
    )
}
