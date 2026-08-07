package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.badges.BadgeLadderId
import com.kungsbackacarcommunity.app.badges.ladderNameRes
import com.kungsbackacarcommunity.app.badges.tierNameRes
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Kronjakt (crown hunt) hub screen. Stateless.
 *
 * NO LONGER A LIST OF CROWNS. Crowns are a MAP collectable — they appear on the
 * map and are collected there (greyed until you are in range, tapped to open a
 * popup). Listing them here as well was a second, worse way to find the same
 * crowns and invited "collect" taps away from the crown's actual location. This
 * page is now the member's Kronjakt HOME: their own standing and this season's
 * top scores — worth opening whether or not a crown is nearby, and read-only (it
 * awards nothing; the backend owns every count).
 *
 * @param statsState the viewer's own stats + this season's leaderboard, or
 *   Loading/Error. Read from the #710 aggregates ([CrownHuntStatsRepository]).
 * @param kronjagare the member's own crown-hunter TIER standing (the badge
 *   ladder), or null while the owner badge listener is still loading — the badge
 *   band is then simply omitted. Never carries a fabricated crowns-collected
 *   count (that counter is backend-only; see [KronjagareStanding]).
 */
@Composable
fun CrownHuntScreen(
    statsState: CrownStatsUiState,
    passesMemberGate: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    kronjagare: KronjagareStanding? = null,
) {
    AeroPage(title = stringResource(R.string.crownHunt_screenTitle), modifier = modifier) {
        if (!passesMemberGate) {
            InfoCard(
                title = stringResource(R.string.subscription_teaserTitle),
                body = stringResource(R.string.subscription_memberRequiredBody),
            )
            return@AeroPage
        }

        // The member's own badge-ladder standing (the "badges" facet of the
        // personal stats). Omitted, not blanked, until the badge listener resolves.
        kronjagare?.let { KronjagareStatsCard(it) }

        when (statsState) {
            CrownStatsUiState.Loading ->
                Text(
                    text = stringResource(R.string.crownHunt_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            CrownStatsUiState.Error ->
                Text(
                    text = stringResource(R.string.crownHunt_statsError),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

            is CrownStatsUiState.Loaded -> {
                if (statsState.personal != null) {
                    PersonalStatsCard(statsState.personal)
                } else {
                    NoStatsYetCard()
                }
                SeasonLeaderboardCard(statsState.board)
            }
        }
    }
}

/** The member's own Kronjakt numbers: crowns, Kronpoäng, season rank, streak. */
@Composable
private fun PersonalStatsCard(stats: CrownPersonalStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.crownHunt_myStatsTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StatRow(
                label = stringResource(R.string.crownHunt_statCrowns),
                value = stats.crownsCollected.toString(),
            )
            StatRow(
                label = stringResource(R.string.crownHunt_statPoints),
                value = stringResource(R.string.crownHunt_kpValue, stats.points),
            )
            StatRow(
                label = stringResource(R.string.crownHunt_statSeasonRank),
                value =
                    stats.seasonRank?.let { stringResource(R.string.crownHunt_rankValue, it) }
                        ?: stringResource(R.string.crownHunt_rankNone),
            )
            StatRow(
                label = stringResource(R.string.crownHunt_statStreak),
                value = stats.streakCurrent.toString(),
            )
            if (stats.seasonsWon > 0) {
                StatRow(
                    label = stringResource(R.string.crownHunt_statSeasonsWon),
                    value = stats.seasonsWon.toString(),
                )
            }
            stats.rarest?.let { rarity ->
                StatRow(
                    label = stringResource(R.string.crownHunt_statRarest),
                    value = stringResource(CrownSpawnMessages.rarityLabelRes(rarity)),
                )
            }
        }
    }
}

/** One "label … value" line in the stats card. */
@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Shown before a member's first collection: an invitation, not a wall of zeros. */
@Composable
private fun NoStatsYetCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.crownHunt_myStatsTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.crownHunt_noStatsYet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** This season's top scores, with the viewer's own row highlighted. */
@Composable
private fun SeasonLeaderboardCard(board: CrownSeasonBoard) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.crownHunt_leaderboardTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (board.rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.crownHunt_leaderboardEmpty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                board.rows.forEach { row -> LeaderboardRow(row) }
            }
        }
    }
}

/** One leaderboard line: "#rank name … N KP", the viewer's own in bold. */
@Composable
private fun LeaderboardRow(row: CrownLeaderboardRow) {
    val weight = if (row.isViewer) FontWeight.Bold else FontWeight.Normal
    val nameColor =
        if (row.isViewer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.crownHunt_rankValue, row.rank),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = weight,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = row.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = weight,
            color = nameColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.crownHunt_kpValue, row.points),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = weight,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The member's own Kronjägare TIER standing: the rung held (or an invitation when
 * none is), the next rung and its crown threshold. Reuses the badge catalog's
 * ladder/tier strings so it can never disagree with the profile badge wall.
 *
 * Shows NO crowns-collected count and NO progress bar: that counter lives on
 * `badgeProgress/{uid}`, denied to every client, so the note explains the rank is
 * tallied server-side rather than inventing a number.
 */
@Composable
private fun KronjagareStatsCard(standing: KronjagareStanding) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.crownHunt_statsTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val ladderName = stringResource(ladderNameRes(BadgeLadderId.KRONJAGARE))
            Text(
                text =
                    standing.highestTier?.let { tier ->
                        stringResource(
                            R.string.crownHunt_statsRankCurrent,
                            ladderName + " " + stringResource(tierNameRes(tier)),
                        )
                    } ?: stringResource(R.string.crownHunt_statsRankNone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (standing.nextTier != null && standing.nextThresholdCrowns != null) {
                Text(
                    text =
                        stringResource(
                            R.string.crownHunt_statsNext,
                            ladderName + " " + stringResource(tierNameRes(standing.nextTier)),
                            standing.nextThresholdCrowns.toString(),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = stringResource(R.string.crownHunt_statsComplete),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(R.string.crownHunt_statsServerNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
