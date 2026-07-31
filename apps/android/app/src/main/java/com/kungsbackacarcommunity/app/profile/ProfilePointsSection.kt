package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
 * This card SUMMARISES; it does not replace the ledger. It shows the balance
 * plus at most [com.kungsbackacarcommunity.app.points.Points.PROFILE_HIGHLIGHT_COUNT]
 * recent, undated CREDITS, whereas
 * [com.kungsbackacarcommunity.app.points.PointsScreen] is the full statement —
 * credits and debits, each dated. So the card is also the way IN to that
 * screen: since the "Points" row was removed from the map-home profile menu
 * (Seb, 2026-07-31 — points belong on the profile page), tapping this card is
 * the app's ONLY route to the ledger, and it must stay wired.
 *
 * Presentational only.
 *
 * @param balance the Kronpoäng balance, or null before the wallet has been read
 *   (renders as 0 — a member with no points genuinely has none).
 * @param recentEarnings newest-first credits, already filtered and capped; empty
 *   renders an encouraging hint instead of a blank list.
 * @param onOpenLedger opens the full Kronpoäng ledger. Null in a build with no
 *   points repository wired, which renders the card as a plain, inert summary
 *   rather than a button that navigates to a permanent spinner.
 */
@Composable
fun ProfilePointsSection(
    balance: Long?,
    recentEarnings: List<PointsEntry>,
    modifier: Modifier = Modifier,
    onOpenLedger: (() -> Unit)? = null,
) {
    val openLabel = stringResource(R.string.profile_pointsViewAll)
    val body: @Composable ColumnScope.() -> Unit = {
        PointsSummaryBody(
            balance = balance,
            recentEarnings = recentEarnings,
            openLabel = if (onOpenLedger != null) openLabel else null,
        )
    }
    if (onOpenLedger != null) {
        Card(
            onClick = onOpenLedger,
            modifier =
                modifier
                    .fillMaxWidth()
                    // A card that navigates has to be ANNOUNCED as navigating:
                    // Role.Button makes TalkBack say "button" instead of reading
                    // it as inert text, and the onClick label replaces the
                    // generic "double tap to activate" with what it actually
                    // does. Merged so the whole card is one focusable target
                    // rather than a dozen unrelated text nodes.
                    //
                    // `action = null` RELABELS the click, it does not remove it.
                    // Material3's Card(onClick = ...) has no onClickLabel
                    // parameter to hang the label on, so the label has to arrive
                    // through semantics; SemanticsConfiguration.set merges two
                    // AccessibilityActions on the same node as
                    // `new.label ?: old.label` / `new.action ?: old.action`, so
                    // the null action falls back to the real one the Card's own
                    // clickable installed. The merged node therefore ends up
                    // with this label AND an invocable action — pinned by
                    // ProfileScreenTest
                    // .pointsCardIsOperableThroughTheAccessibilityClickAction,
                    // which performs the SEMANTICS action (what TalkBack does)
                    // rather than a touch and asserts onOpenLedger fires.
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        onClick(label = openLabel, action = null)
                    },
            content = body,
        )
    } else {
        Card(modifier = modifier.fillMaxWidth(), content = body)
    }
}

/**
 * The card's contents, shared by the tappable and inert variants so the two
 * cannot drift apart.
 *
 * @param openLabel the visible "see the whole ledger" affordance, or null when
 *   the card does not navigate (no trailing row is drawn at all then — an arrow
 *   that leads nowhere is worse than no arrow).
 */
@Composable
private fun PointsSummaryBody(
    balance: Long?,
    recentEarnings: List<PointsEntry>,
    openLabel: String?,
) {
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

        if (openLabel != null) {
            // The sighted affordance for the same tap the semantics above
            // announce: without it the card looks like the read-only summary it
            // used to be, and the ledger would be discoverable only by chance.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = openLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    // Decorative: the label beside it already says where this
                    // goes, and the card's merged semantics carry the action.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(KccSpacing.s5),
                )
            }
        }
    }
}
