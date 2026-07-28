package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.points.PointsEntry

/**
 * Kronpoäng on the member's own profile — "how active you have been" — with the
 * few most recent EARNINGS underneath so the number has a reason attached
 * ("Sparad körning +15 p", "Märke upplåst: Kronjägare Brons +25 p").
 *
 * Both reads are owner-scoped and already available to the client: the balance
 * is the single `pointsLedger/{uid}` document, and the recent list is the
 * existing bounded, newest-first listener on its `entries` subcollection — the
 * same one the full Kronpoäng screen uses, so the profile adds no new query
 * shape and no index. Debits are deliberately excluded (see
 * [com.kungsbackacarcommunity.app.points.Points.recentEarnings]).
 *
 * Presentational only.
 *
 * @param balance the Kronpoäng balance, or null before the wallet has been read
 *   (renders as 0 — a member with no points genuinely has none).
 * @param recentEarnings newest-first credits, already filtered and capped; empty
 *   renders an encouraging hint instead of a blank list.
 */
@Composable
fun ProfilePointsSection(
    balance: Long?,
    recentEarnings: List<PointsEntry>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Text(
                text = stringResource(R.string.profile_pointsTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
                Text(
                    text = (balance ?: 0L).toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.profile_pointsUnit),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = KccSpacing.s1),
                )
            }
            Text(
                text = stringResource(R.string.profile_pointsSubtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text(
                text = stringResource(R.string.profile_pointsRecentTitle),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (recentEarnings.isEmpty()) {
                Text(
                    text = stringResource(R.string.profile_pointsRecentEmpty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                recentEarnings.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.profile_pointsAmount, entry.amount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
