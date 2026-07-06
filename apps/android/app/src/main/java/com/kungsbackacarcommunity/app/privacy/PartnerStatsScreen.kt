package com.kungsbackacarcommunity.app.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

/**
 * Anonymised partner-statistics opt-in (Phase 12 slice 19). Stateless apart
 * from the pending toggle; explains the privacy stance and saves the choice.
 */
@Composable
fun PartnerStatsScreen(
    currentOptIn: Boolean?,
    saveStatus: PartnerStatsSaveStatus,
    onSave: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Seed the pending toggle from the observed value ONCE — decoupled from
    // later Firestore emissions so a live update doesn't overwrite an edit the
    // user is in the middle of making.
    var pending by rememberSaveable { mutableStateOf(currentOptIn ?: false) }
    var seeded by rememberSaveable { mutableStateOf(currentOptIn != null) }
    LaunchedEffect(currentOptIn) {
        if (!seeded && currentOptIn != null) {
            pending = currentOptIn
            seeded = true
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.privacySettings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.privacySettings_partnerStatsTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.privacySettings_partnerStatsExplainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.privacySettings_partnerStatsNotice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The whole row is the toggle target with merged semantics, so
            // TalkBack reads the description as the switch's label (Role.Switch).
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = pending,
                            role = Role.Switch,
                            onValueChange = { pending = it },
                        )
                        .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.privacySettings_partnerStatsBody),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                // Null callback — the row's toggleable owns the interaction.
                Switch(checked = pending, onCheckedChange = null)
            }
            Text(
                text = stringResource(R.string.privacySettings_partnerStatsNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (saveStatus) {
                PartnerStatsSaveStatus.Saved ->
                    Text(
                        text = stringResource(R.string.privacySettings_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                PartnerStatsSaveStatus.Failed ->
                    Text(
                        text = stringResource(R.string.privacySettings_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                else -> Unit
            }

            Button(
                onClick = { onSave(pending) },
                enabled = saveStatus != PartnerStatsSaveStatus.Saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.privacySettings_saveButton))
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.profile_back))
            }
        }
    }
}
