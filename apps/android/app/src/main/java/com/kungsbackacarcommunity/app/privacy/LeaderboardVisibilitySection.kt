package com.kungsbackacarcommunity.app.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * Leaderboard-visibility opt-out toggle (Leaderboard PR4). Mirrors the
 * partner-stats toggle exactly — same read/write plumbing (a rules-validated
 * owner write to `userPrivate/{uid}`) and the same visual pattern (row-as-switch
 * with merged semantics, Save disabled until a definitive read).
 *
 * The switch models "shown on the leaderboard" (default-on); the stored field is
 * the inverse `leaderboardOptOut`, so [onSave] receives the shown value and the
 * caller persists `optOut = !shown`. Opting out hides the member on the in-app
 * leaderboard AND on the public website (same generator).
 */
@Composable
fun LeaderboardVisibilitySection(
    visibility: LeaderboardVisibilityState,
    saveStatus: LeaderboardVisibilitySaveStatus,
    onSave: (shown: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Seed the pending toggle from the first DEFINITIVE read ONCE — decoupled
    // from later Firestore emissions so a live update doesn't overwrite an edit
    // in progress. Default-shown / opt-out: the toggle shows ON (shown) while
    // unresolved, but Save stays DISABLED until a definitive read ([seeded]) so a
    // transient read error (Unknown) can never persist the default-shown value
    // over a member who has explicitly opted out.
    var pendingShown by rememberSaveable { mutableStateOf(true) }
    var seeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(visibility) {
        if (!seeded) {
            when (visibility) {
                LeaderboardVisibilityState.DefaultShown -> {
                    pendingShown = true
                    seeded = true
                }
                is LeaderboardVisibilityState.Chosen -> {
                    pendingShown = !visibility.optOut
                    seeded = true
                }
                LeaderboardVisibilityState.Unknown -> Unit
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.privacySettings_leaderboardTitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.privacySettings_leaderboardExplainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The whole row is the toggle target with merged semantics, so TalkBack
        // reads the description as the switch's label (Role.Switch).
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = pendingShown,
                        role = Role.Switch,
                        onValueChange = { pendingShown = it },
                    )
                    .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.privacySettings_leaderboardBody),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            // Null callback — the row's toggleable owns the interaction.
            Switch(checked = pendingShown, onCheckedChange = null)
        }

        when (saveStatus) {
            LeaderboardVisibilitySaveStatus.Saved ->
                Text(
                    text = stringResource(R.string.privacySettings_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

            LeaderboardVisibilitySaveStatus.Failed ->
                Text(
                    text = stringResource(R.string.privacySettings_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

            else -> Unit
        }

        Button(
            onClick = { onSave(pendingShown) },
            // Disabled until a definitive read: never let the default-shown value
            // be persisted over an unresolved/errored (Unknown) read.
            enabled = seeded && saveStatus != LeaderboardVisibilitySaveStatus.Saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.privacySettings_saveButton))
        }
    }
}
