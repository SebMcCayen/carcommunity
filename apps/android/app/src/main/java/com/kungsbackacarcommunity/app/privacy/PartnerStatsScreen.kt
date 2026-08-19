package com.kungsbackacarcommunity.app.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Anonymised partner-statistics opt-in (Phase 12 slice 19). Stateless apart
 * from the pending toggle; explains the privacy stance and saves the choice.
 */
@Composable
fun PartnerStatsScreen(
    consent: PartnerStatsConsentState,
    saveStatus: PartnerStatsSaveStatus,
    onSave: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Leaderboard-visibility opt-out (Leaderboard PR4). Optional so existing
    // callers/tests are unaffected; the section renders only when a save handler
    // is supplied.
    leaderboardVisibility: LeaderboardVisibilityState = LeaderboardVisibilityState.Unknown,
    leaderboardSaveStatus: LeaderboardVisibilitySaveStatus = LeaderboardVisibilitySaveStatus.Idle,
    onSaveLeaderboard: ((shown: Boolean) -> Unit)? = null,
) {
    // Seed the pending toggle from the first DEFINITIVE read ONCE — decoupled
    // from later Firestore emissions so a live update doesn't overwrite an edit
    // the user is in the middle of making. Anonymised partner statistics are
    // DEFAULT-ON / opt-out, so the toggle shows ON while unresolved; but Save
    // stays DISABLED until a definitive read ([seeded]) so a transient read
    // error (Unknown) can never persist the default-on value over an explicitly
    // opted-out member's real choice.
    var pending by rememberSaveable { mutableStateOf(true) }
    var seeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(consent) {
        if (!seeded) {
            when (consent) {
                PartnerStatsConsentState.DefaultOn -> {
                    pending = true
                    seeded = true
                }
                is PartnerStatsConsentState.Chosen -> {
                    pending = consent.optIn
                    seeded = true
                }
                PartnerStatsConsentState.Unknown -> Unit
            }
        }
    }

    AeroPage(
        title = stringResource(R.string.privacySettings_title),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                // Disabled until a definitive read: never let the default-on value
                // be persisted over an unresolved/errored (Unknown) read.
                enabled = seeded && saveStatus != PartnerStatsSaveStatus.Saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.privacySettings_saveButton))
            }

            if (onSaveLeaderboard != null) {
                LeaderboardVisibilitySection(
                    visibility = leaderboardVisibility,
                    saveStatus = leaderboardSaveStatus,
                    onSave = onSaveLeaderboard,
                )
            }
    }
}
