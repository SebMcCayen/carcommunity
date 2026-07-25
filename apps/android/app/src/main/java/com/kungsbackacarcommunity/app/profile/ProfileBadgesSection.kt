package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.badges.BADGE_LADDERS
import com.kungsbackacarcommunity.app.badges.BADGE_TIER_POINTS
import com.kungsbackacarcommunity.app.badges.BadgeGlyph
import com.kungsbackacarcommunity.app.badges.BadgeLadder
import com.kungsbackacarcommunity.app.badges.BadgeMedallion
import com.kungsbackacarcommunity.app.badges.BadgeRung
import com.kungsbackacarcommunity.app.badges.BadgeShowcase
import com.kungsbackacarcommunity.app.badges.BadgeTier
import com.kungsbackacarcommunity.app.badges.LadderProgress
import com.kungsbackacarcommunity.app.badges.MilestoneBadge
import com.kungsbackacarcommunity.app.badges.badgeNameRes
import com.kungsbackacarcommunity.app.badges.formatLadderValue
import com.kungsbackacarcommunity.app.badges.ladderNameRes
import com.kungsbackacarcommunity.app.badges.ladderRequirementRes
import com.kungsbackacarcommunity.app.badges.ladderTaglineRes
import com.kungsbackacarcommunity.app.badges.tierNameRes
import com.kungsbackacarcommunity.app.design.KccSpacing
import java.text.DateFormat
import java.util.Date

/**
 * The member's own badge wall on their profile — "a way to show off what you
 * have achieved", plus the climb to the next rung.
 *
 * OWN PROFILE ONLY. `users/{uid}/badges` is an owner-only read; showing another
 * member's badges would leak their streaks, distance driven and meets attended
 * and needs a deliberate rules change that has not been made. This composable is
 * therefore only ever handed the signed-in member's own [BadgeShowcase], and the
 * read-only member-profile screen carries a commented seam rather than a call.
 *
 * Three bands, top to bottom:
 *  1. HIGHEST TIER PER LADDER — six medallions, so the ladders read as ladders
 *     at a glance. An unstarted ladder shows its first rung greyed, never a gap,
 *     which is what turns a brand-new member's empty wall into a menu of goals.
 *  2. NEXT TIER — every unfinished ladder with its target and requirement, and a
 *     progress bar wherever the app can honestly observe the counter (see
 *     [com.kungsbackacarcommunity.app.badges.BadgeCounters]; the authoritative
 *     `badgeProgress` counters are backend-only). A finished ladder says so
 *     instead of showing an empty bar.
 *  3. ALL AWARDS — an expander with every rung of every ladder plus the
 *     standalone milestones, earned lit and unearned greyed.
 *
 * Tapping any medallion opens its detail: name, what it takes, and either the
 * date it was unlocked or how to unlock it.
 *
 * Presentational only — renders a pre-assembled model and reads no backend.
 *
 * @param showcase the assembled wall, or null while the owner badge listener is
 *   still loading (renders nothing; the rest of the profile is useful already).
 */
@Composable
fun ProfileBadgesSection(
    showcase: BadgeShowcase?,
    modifier: Modifier = Modifier,
) {
    if (showcase == null) return
    var selected by remember { mutableStateOf<BadgeDetail?>(null) }
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Text(
                text = stringResource(R.string.badgeShowcase_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    stringResource(
                        R.string.badgeShowcase_subtitle,
                        showcase.earnedCount,
                        showcase.totalCount,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!showcase.hasAnyBadge) {
                // Nothing earned yet: lead with the invitation, then the same
                // locked wall below reads as a menu of what is on offer.
                Text(
                    text = stringResource(R.string.badgeShowcase_emptyTitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.badgeShowcase_emptyBody),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LadderMedallionGrid(
                ladders = showcase.ladders,
                onSelect = { selected = it },
            )

            val unfinished = showcase.laddersInProgress
            if (unfinished.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.badgeShowcase_progressTitle),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                unfinished.forEach { progress -> LadderProgressRow(progress) }
            }

            val finished = showcase.ladders.filter { it.isComplete }
            finished.forEach { progress ->
                Text(
                    text =
                        stringResource(ladderNameRes(progress.ladder.id)) +
                            " · " +
                            stringResource(R.string.badgeShowcase_ladderComplete),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (expanded) R.string.badgeShowcase_showLess else R.string.badgeShowcase_showAll,
                    ),
                )
            }
            if (expanded) {
                AllAwardsGrid(showcase = showcase, onSelect = { selected = it })
            }
        }
    }

    selected?.let { detail ->
        BadgeDetailDialog(detail = detail, onDismiss = { selected = null })
    }
}

// ---------------------------------------------------------------------------
// Bands
// ---------------------------------------------------------------------------

/** One medallion per ladder — the highest tier held, or the locked first rung. */
@Composable
private fun LadderMedallionGrid(
    ladders: List<LadderProgress>,
    onSelect: (BadgeDetail) -> Unit,
) {
    GridRows(items = ladders, perRow = 3) { progress ->
        val rung = progress.displayRung
        // Resolved during composition — a click lambda is not a composable scope
        // and cannot call stringResource.
        val detail = rememberRungDetail(progress.ladder, rung, !progress.isLocked)
        Column(
            modifier =
                Modifier
                    .clickable(role = Role.Button) { onSelect(detail) }
                    .padding(vertical = KccSpacing.s1),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        ) {
            BadgeMedallion(
                glyph = BadgeGlyph.Ladder(progress.ladder.id),
                tier = rung.tier,
                earned = !progress.isLocked,
                contentDescription =
                    stringResource(
                        if (progress.isLocked) {
                            R.string.badgeShowcase_medallionLocked
                        } else {
                            R.string.badgeShowcase_medallionEarned
                        },
                        rungDisplayName(progress.ladder, rung),
                    ),
            )
            Text(
                text = stringResource(ladderNameRes(progress.ladder.id)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text =
                    if (progress.isLocked) {
                        stringResource(R.string.badgeShowcase_noTierYet)
                    } else {
                        stringResource(tierNameRes(rung.tier))
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The climb: target rung, what it takes, and a bar when the counter is knowable. */
@Composable
private fun LadderProgressRow(progress: LadderProgress) {
    val next = progress.nextRung ?: return
    val ladder = progress.ladder
    val threshold = formatLadderValue(ladder.unit, next.threshold)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(ladderNameRes(ladder.id)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.badgeShowcase_nextTier, stringResource(tierNameRes(next.tier))),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(ladderRequirementRes(ladder.id), threshold),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val fraction = progress.fractionToNext
        val observed = progress.observedValue
        if (fraction != null && observed != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text =
                    stringResource(
                        R.string.badgeShowcase_progressCount,
                        formatLadderValue(ladder.unit, observed),
                        threshold,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // No honest client-readable counter for this ladder — say so rather
            // than draw a bar the app cannot back up.
            Text(
                text = stringResource(ladderTaglineRes(ladder.id)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Every rung of every ladder plus the standalone milestones. */
@Composable
private fun AllAwardsGrid(showcase: BadgeShowcase, onSelect: (BadgeDetail) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
        BADGE_LADDERS.forEach { ladder ->
            val progress = showcase.ladders.first { it.ladder.id == ladder.id }
            val earnedKeys = progress.earnedRungs.map { it.badgeKey }.toSet()
            Text(
                text = stringResource(ladderNameRes(ladder.id)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            GridRows(items = ladder.rungs, perRow = 4) { rung ->
                val earned = rung.badgeKey in earnedKeys
                val detail =
                    rememberRungDetail(ladder, rung, earned, showcase.awardedAtByKey[rung.badgeKey])
                MedallionTile(
                    glyph = BadgeGlyph.Ladder(ladder.id),
                    tier = rung.tier,
                    earned = earned,
                    label = stringResource(tierNameRes(rung.tier)),
                    onClick = { onSelect(detail) },
                )
            }
        }

        if (showcase.milestones.isNotEmpty()) {
            Text(
                text = stringResource(R.string.badgeShowcase_milestonesTitle),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            GridRows(items = showcase.milestones, perRow = 4) { milestone ->
                val name = milestoneName(milestone)
                val detail =
                    BadgeDetail(
                        glyph = BadgeGlyph.Milestone(milestone.key),
                        tier = null,
                        name = name,
                        requirement = null,
                        pointsReward = 0,
                        earned = true,
                        awardedAtMillis = milestone.awardedAtMillis,
                    )
                MedallionTile(
                    glyph = BadgeGlyph.Milestone(milestone.key),
                    tier = null,
                    earned = true,
                    label = name,
                    onClick = { onSelect(detail) },
                )
            }
        }
    }
}

@Composable
private fun MedallionTile(
    glyph: BadgeGlyph,
    tier: BadgeTier?,
    earned: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = KccSpacing.s1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
    ) {
        BadgeMedallion(
            glyph = glyph,
            tier = tier,
            earned = earned,
            contentDescription =
                stringResource(
                    if (earned) {
                        R.string.badgeShowcase_medallionEarned
                    } else {
                        R.string.badgeShowcase_medallionLocked
                    },
                    label,
                ),
            size = 40.dp,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (earned) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A fixed-column grid built from Rows.
 *
 * Deliberately not a LazyVerticalGrid: this section renders inside the profile's
 * vertically scrolling column, where a nested lazy grid has unbounded height.
 * The item counts here are small and fixed (3–6 per grid), so plain Rows are
 * both correct and cheaper. The trailing row is padded with empty weights so
 * items stay column-aligned instead of centring.
 */
@Composable
private fun <T> GridRows(
    items: List<T>,
    perRow: Int,
    item: @Composable (T) -> Unit,
) {
    items.chunked(perRow).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            row.forEach { value ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    item(value)
                }
            }
            repeat(perRow - row.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Detail
// ---------------------------------------------------------------------------

/** Everything the detail dialog needs, resolved at tap time. */
data class BadgeDetail(
    val glyph: BadgeGlyph,
    val tier: BadgeTier?,
    val name: String,
    /** Requirement sentence, or null for a standalone milestone. */
    val requirement: String?,
    val pointsReward: Int,
    val earned: Boolean,
    val awardedAtMillis: Long?,
)

/** Resolves a rung's localized detail during composition, ready for a click. */
@Composable
private fun rememberRungDetail(
    ladder: BadgeLadder,
    rung: BadgeRung,
    earned: Boolean,
    awardedAtMillis: Long? = null,
): BadgeDetail =
    BadgeDetail(
        glyph = BadgeGlyph.Ladder(ladder.id),
        tier = rung.tier,
        name = rungDisplayName(ladder, rung),
        requirement =
            stringResource(
                ladderRequirementRes(ladder.id),
                formatLadderValue(ladder.unit, rung.threshold),
            ),
        pointsReward = BADGE_TIER_POINTS[rung.tier] ?: 0,
        earned = earned,
        awardedAtMillis = awardedAtMillis,
    )

@Composable
private fun BadgeDetailDialog(detail: BadgeDetail, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.badgeShowcase_close)) }
        },
        icon = {
            BadgeMedallion(
                glyph = detail.glyph,
                tier = detail.tier,
                earned = detail.earned,
                contentDescription = null,
                size = 64.dp,
            )
        },
        title = { Text(detail.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                detail.requirement?.let { requirement ->
                    Text(
                        text = requirement,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (detail.earned) {
                    detail.awardedAtMillis?.let { millis ->
                        Text(
                            text =
                                stringResource(
                                    R.string.badgeShowcase_awardedOn,
                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis)),
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.badgeShowcase_howToEarn),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (detail.pointsReward > 0) {
                        Text(
                            text = stringResource(R.string.badgeShowcase_reward, detail.pointsReward),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.badgeShowcase_progressUnavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Naming
// ---------------------------------------------------------------------------

/**
 * "Kronjägare Silver" — composed from the ladder and tier names rather than
 * carrying 23 separate name strings, which is exactly how the backend builds the
 * denormalized `name` on the award document.
 */
@Composable
private fun rungDisplayName(ladder: BadgeLadder, rung: BadgeRung): String =
    stringResource(ladderNameRes(ladder.id)) + " " + stringResource(tierNameRes(rung.tier))

@Composable
private fun milestoneName(milestone: MilestoneBadge): String {
    val res = badgeNameRes(milestone.key)
    return if (res != null) stringResource(res) else (milestone.fallbackName ?: milestone.key)
}
