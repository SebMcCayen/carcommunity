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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.launch

/** Parent inserts this into Settings; ownership/rules remain the write authority. */
@Composable
fun MembershipMapRingSetting(repository: ProfileRepository, uid: String) = key(repository, uid) {
    val state by remember(repository, uid) { repository.observeProfile(uid) }.collectAsState(initial = ProfileState.Loading)
    val coordinator = remember(repository, uid) {
        MembershipMapRingPreferenceCoordinator { repository.updateShowMembershipMapRing(uid, it) }
    }
    val status by coordinator.status.collectAsState()
    val scope = rememberCoroutineScope()
    MembershipMapRingSettingRow((state as? ProfileState.Loaded)?.profile?.membershipMapRing, status) {
        scope.launch { coordinator.save(it) }
    }
}

@Composable
fun MembershipMapRingSettingRow(ring: MembershipMapRing?, status: ProfileEditStatus, onToggle: (Boolean) -> Unit) {
    val title = stringResource(R.string.membershipMapRing_settingTitle)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Switch(checked = ring?.show ?: true, onCheckedChange = onToggle,
                enabled = ring != null && status != ProfileEditStatus.Saving,
                modifier = Modifier.testTag("membership_map_ring_setting").semantics { contentDescription = title })
        }
        Text(stringResource(R.string.membershipMapRing_settingBody), style = MaterialTheme.typography.bodySmall)
        if (status == ProfileEditStatus.Failed) Text(stringResource(R.string.membershipMapRing_saveFailed), color = MaterialTheme.colorScheme.error)
    }
}
