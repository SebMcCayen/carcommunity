package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.launch

@Composable
fun SupporterBadgeSetting(repository: ProfileRepository, uid: String) {
    val profileState by remember(repository, uid) { repository.observeProfile(uid) }
        .collectAsState(initial = ProfileState.Loading)
    val coordinator = remember(repository, uid) {
        SupporterBadgePreferenceCoordinator { repository.updateShowSupporterBadge(uid, it) }
    }
    val status by coordinator.status.collectAsState()
    val scope = rememberCoroutineScope()
    SupporterBadgeSettingRow(
        badge = (profileState as? ProfileState.Loaded)?.profile?.supporterBadge,
        status = status,
        onToggle = { scope.launch { coordinator.save(it) } },
    )
}

@Composable
fun SupporterBadgeSettingRow(badge: SupporterBadge?, status: ProfileEditStatus, onToggle: (Boolean) -> Unit) {
    val title = stringResource(R.string.supporterBadge_settingTitle)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Switch(
                checked = badge?.show ?: true,
                onCheckedChange = onToggle,
                enabled = badge != null && status != ProfileEditStatus.Saving,
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
        Text(stringResource(R.string.supporterBadge_settingBody), style = MaterialTheme.typography.bodySmall)
        if (status == ProfileEditStatus.Failed) {
            Text(stringResource(R.string.supporterBadge_saveFailed), color = MaterialTheme.colorScheme.error)
        }
    }
}
