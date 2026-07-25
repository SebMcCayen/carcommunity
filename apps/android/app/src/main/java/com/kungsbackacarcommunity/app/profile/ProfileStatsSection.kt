package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.drives.DriveFormatters
import java.text.DateFormat
import java.util.Date

/**
 * "My stats" summary card on the owner's own profile.
 *
 * Presentational only: it renders the pre-assembled [ProfileStatsSummary] and
 * reads no backend of its own. It matches the app's stat idiom (a titled
 * Material [Card] of label/value rows, the same shape "Your driving"
 * ([com.kungsbackacarcommunity.app.drives.DriveStatsScreen]) and the badges
 * screen use) and reuses [DriveFormatters] for every distance/time figure so the
 * numbers read identically to the History screens.
 *
 * A member with no drives and no awards gets a single encouraging empty card
 * instead of a wall of zeroes.
 *
 * @param summary the assembled figures, or null while the underlying owner
 *   reads are still loading (renders nothing — the rest of the profile is
 *   already useful on its own).
 */
@Composable
fun ProfileStatsSection(
    summary: ProfileStatsSummary?,
    modifier: Modifier = Modifier,
) {
    if (summary == null) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Text(
                text = stringResource(R.string.profile_statsTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (!summary.hasActivity) {
                EmptyStats()
                return@Column
            }

            StatRow(
                stringResource(R.string.savedDrives_statsTotalDrives),
                summary.totalDrives.toString(),
            )
            StatRow(
                stringResource(R.string.savedDrives_statsTotalDistance),
                DriveFormatters.formatDistance(summary.totalDistanceMeters),
            )
            StatRow(
                stringResource(R.string.savedDrives_statsTotalTime),
                DriveFormatters.formatDuration(summary.totalDurationSeconds),
            )
            // Badge count and Kronpoäng balance are NOT repeated here: both now
            // have their own richer sections on this same profile
            // ([ProfileBadgesSection] shows the wall and the next rung,
            // [ProfilePointsSection] the balance and what earned it), and a
            // second bare number would read as a duplicate. They are still part
            // of [ProfileStatsSummary] because `hasActivity` is decided by them.
            summary.memberSinceMillis?.let { millis ->
                StatRow(
                    stringResource(R.string.profile_statsMemberSince),
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis)),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.EmptyStats() {
    Text(
        text = stringResource(R.string.profile_statsEmptyTitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.profile_statsEmptyBody),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
