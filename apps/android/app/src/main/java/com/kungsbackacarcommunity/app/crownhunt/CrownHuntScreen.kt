package com.kungsbackacarcommunity.app.crownhunt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.badges.BadgeLadderId
import com.kungsbackacarcommunity.app.badges.ladderNameRes
import com.kungsbackacarcommunity.app.badges.tierNameRes
import com.kungsbackacarcommunity.app.shell.AeroPage
import com.kungsbackacarcommunity.app.shell.LocalAeroBackAvailable

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
 *   band is then simply omitted. Its crowns-collected figure, when present, is the
 *   client-readable all-time leaderboard mirror, never a fabricated one (see
 *   [KronjagareStanding.crownsCollected]).
 * @param perksEnabled the `crownHuntPerks` flag. DEFAULT FALSE, and load-bearing:
 *   when off (the shipped default) the shop tab is NOT rendered at all — the page
 *   is byte-identical to the pre-shop hub. Only when an operator switches the flag
 *   on does the "Butik" tab appear, so the whole shop ships dark.
 * @param liveShareScoringEnabled the `crownHuntLiveShareScoring` flag. DEFAULT
 *   FALSE: when off the Instructions surface omits the live-share scoring section
 *   entirely, so the copy never describes a rule that is not in force. When on it
 *   adds the "go live for full points" section (backend halves off-live claims).
 * @param shopState the buy surface's combined catalog + inventory + balance state
 *   ([PerkShop.toUiState]). Ignored while [perksEnabled] is false.
 * @param buyStatus the in-flight/terminal state of the current buy, driving the
 *   per-row spinner and the result banner.
 * @param onBuyPerk invoked when the member taps "Köp" on a row.
 */
@Composable
fun CrownHuntScreen(
    statsState: CrownStatsUiState,
    passesMemberGate: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    kronjagare: KronjagareStanding? = null,
    perksEnabled: Boolean = false,
    liveShareScoringEnabled: Boolean = false,
    shopState: PerkShopUiState = PerkShopUiState.Loading,
    buyStatus: PerkBuyStatus = PerkBuyStatus.Idle,
    onBuyPerk: (PerkShopItem) -> Unit = {},
) {
    // The Instructions surface is a self-contained sub-view of this same route: a
    // full-screen page rendered in place of the hub, dismissed by the shared Aero
    // Back affordance (which fires the back dispatcher, intercepted here) so it
    // returns to the hub rather than popping the whole Kronjakt route. No new nav
    // graph entry and no repository data — the copy is static.
    var showInstructions by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showInstructions) { showInstructions = false }
    if (showInstructions) {
        // Provide LocalAeroBackAvailable = true AROUND the Instructions surface so
        // its AeroPage renders the pinned in-app Back arrow regardless of the
        // ambient routing context. RouteHost already provides this true on the live
        // path, but providing it here makes the surface's back affordance
        // self-contained instead of depending on an ancestor two levels up — the
        // arrow's tap fires the back dispatcher, caught by the BackHandler above,
        // returning to the hub.
        CompositionLocalProvider(LocalAeroBackAvailable provides true) {
            CrownHuntInstructionsScreen(
                modifier = modifier,
                liveShareScoringEnabled = liveShareScoringEnabled,
            )
        }
        return
    }

    AeroPage(title = stringResource(R.string.crownHunt_screenTitle), modifier = modifier) {
        if (!passesMemberGate) {
            InfoCard(
                title = stringResource(R.string.subscription_teaserTitle),
                body = stringResource(R.string.subscription_memberRequiredBody),
            )
            return@AeroPage
        }

        // Flag OFF (the shipped default): no tab bar at all — render exactly the
        // pre-shop hub so the shop is invisible until an operator enables it.
        if (!perksEnabled) {
            CrownHuntHubContent(statsState, kronjagare, onShowInstructions = { showInstructions = true })
            return@AeroPage
        }

        // Flag ON: a two-tab hub — the read-only stats home, and the shop.
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.crownHunt_tabHome)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.crownHunt_tabShop)) },
            )
        }
        when (selectedTab) {
            1 -> PerkShopContent(shopState, buyStatus, onBuyPerk)
            else -> CrownHuntHubContent(statsState, kronjagare, onShowInstructions = { showInstructions = true })
        }
    }
}

/**
 * The read-only Kronjakt HOME: the member's badge standing and this season's stats
 * + leaderboard. Extracted so it renders identically whether or not the shop tab
 * bar is present above it. The "how they work" crown legend is NOT here — it lives
 * on the Instructions surface ([CrownHuntInstructionsScreen]), reached via the
 * Instructions button, so this hub has a single place that explains crowns.
 */
@Composable
private fun ColumnScope.CrownHuntHubContent(
    statsState: CrownStatsUiState,
    kronjagare: KronjagareStanding?,
    onShowInstructions: () -> Unit,
) {
    // A clearly-placed entry into the full rules/description surface, at the very
    // top of the hub so a first-time member finds "how does this work?" before the
    // stats. Opens the read-only [CrownHuntInstructionsScreen].
    OutlinedButton(
        onClick = onShowInstructions,
        modifier = Modifier.fillMaxWidth().testTag(CrownHuntInstructionsButtonTag),
    ) {
        Text(stringResource(R.string.crownHunt_instrButton))
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

/** testTag on the "Instructions" button, for UI tests. */
const val CrownHuntInstructionsButtonTag = "crownHuntInstructionsButton"

/**
 * The full Crown Hunt rules/description surface, opened from the hub's
 * "Instructions" button. A read-only, scrollable [AeroPage] — it awards nothing
 * and reads no repository data; the copy is static and localized (sv primary +
 * en) via the `crownHunt.instr*` contract keys.
 *
 * The prose is grounded in the backend but deliberately PLAYER-FRIENDLY:
 *  - rarities/points/shared-vs-exclusive mirror the rarity table
 *    (`crown-spawn-core.ts`: common/uncommon shared, rare/legendary exclusive;
 *    rarer = more KP) and the Kronjägare ladder thresholds (10/50/250/1000);
 *  - spawn frequency is intentionally VAGUE — no replenish cadence, density
 *    formula or TTL hours — so the exact mechanics stay part of the hunt.
 *
 * Dismissed by the shared Aero Back arrow: the parent [CrownHuntScreen] provides
 * `LocalAeroBackAvailable = true` around this surface so the arrow is always
 * visible, and intercepts its back dispatch (a [BackHandler]) to return to the
 * hub rather than pop the whole route.
 */
@Composable
private fun CrownHuntInstructionsScreen(
    modifier: Modifier = Modifier,
    liveShareScoringEnabled: Boolean = false,
) {
    AeroPage(title = stringResource(R.string.crownHunt_instrTitle), modifier = modifier) {
        InstructionSection(
            title = stringResource(R.string.crownHunt_instrIntroTitle),
            body = stringResource(R.string.crownHunt_instrIntroBody),
        )
        InstructionSection(
            title = stringResource(R.string.crownHunt_instrCollectTitle),
            body = stringResource(R.string.crownHunt_instrCollectBody),
        )
        // The crown-families legend ("Crowns – how they work": placed vs automatic
        // crowns and the disappear-after-collect rule). It used to sit inline on the
        // Kronjakt hub; it now lives ONLY here so Instructions is the single place to
        // read how crowns work. Placed above the rarity/shared/expire detail because
        // it frames the two families those sections then expand on.
        CrownLegendCard()
        InstructionRaritiesSection()
        InstructionSection(
            title = stringResource(R.string.crownHunt_instrSharedTitle),
            body = stringResource(R.string.crownHunt_instrSharedBody),
        )
        InstructionSection(
            title = stringResource(R.string.crownHunt_instrExpireTitle),
            body = stringResource(R.string.crownHunt_instrExpireBody),
        )
        InstructionSection(
            title = stringResource(R.string.crownHunt_instrSpawnTitle),
            body = stringResource(R.string.crownHunt_instrSpawnBody),
        )
        InstructionSection(
            title = stringResource(R.string.crownHunt_instrPointsTitle),
            body = stringResource(R.string.crownHunt_instrPointsBody),
        )
        // Only rendered while the backend rule is live, so the copy never
        // describes a scoring rule that is switched off.
        if (liveShareScoringEnabled) {
            InstructionSection(
                title = stringResource(R.string.crownHunt_instrLiveTitle),
                body = stringResource(R.string.crownHunt_instrLiveBody),
            )
        }
        InstructionSection(
            title = stringResource(R.string.crownHunt_instrRankTitle),
            body = stringResource(R.string.crownHunt_instrRankBody),
        )
    }
}

/** One "heading over a paragraph" instruction card. */
@Composable
private fun InstructionSection(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
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

/**
 * The rarities card: an intro line, then one bold-rarity-name + description row per
 * tier. Reuses the shared rarity name strings so it can never disagree with the
 * crown popups.
 */
@Composable
private fun InstructionRaritiesSection() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.crownHunt_instrRaritiesTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.crownHunt_instrRaritiesIntro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LegendEntry(
                title = stringResource(R.string.crownHunt_rarityCommon),
                body = stringResource(R.string.crownHunt_instrRarityCommonBody),
            )
            LegendEntry(
                title = stringResource(R.string.crownHunt_rarityUncommon),
                body = stringResource(R.string.crownHunt_instrRarityUncommonBody),
            )
            LegendEntry(
                title = stringResource(R.string.crownHunt_rarityRare),
                body = stringResource(R.string.crownHunt_instrRarityRareBody),
            )
            LegendEntry(
                title = stringResource(R.string.crownHunt_rarityLegendary),
                body = stringResource(R.string.crownHunt_instrRarityLegendaryBody),
            )
        }
    }
}

/**
 * Explains the two crown families and what happens after collection. Static,
 * read-only reference copy — awards nothing. Grounded in the backend:
 *  - Placed crowns = `crownHuntPoints` (admin/event, fixed `rewardPoints` KP).
 *    Two independent knobs: a `repeatRule` (once / daily / weekly) sets how often
 *    the SAME member may re-collect, and a `maxCollectors` cap (null = unlimited)
 *    bounds how many DISTINCT members may collect — `status` → `ended` (leaves the
 *    map) once that cap is reached.
 *  - Automatic crowns = `crownSpawns` (rarity table with rising KP + rising TTL,
 *    every spawn has an `expiresAt` and vanishes on its own; legendary is the
 *    `exclusive` mode that the first taker removes for everyone).
 */
@Composable
private fun CrownLegendCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.crownHunt_legendTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LegendEntry(
                title = stringResource(R.string.crownHunt_legendPlacedTitle),
                body = stringResource(R.string.crownHunt_legendPlacedBody),
            )
            LegendEntry(
                title = stringResource(R.string.crownHunt_legendSpawnedTitle),
                body = stringResource(R.string.crownHunt_legendSpawnedBody),
            )
            LegendEntry(
                title = stringResource(R.string.crownHunt_legendDisappearTitle),
                body = stringResource(R.string.crownHunt_legendDisappearBody),
            )
        }
    }
}

/** One "bold heading over a paragraph" block in the crown legend. */
@Composable
private fun LegendEntry(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
 * Shows honest progress toward the next rung ("9 / 10 crowns") WHEN the lifetime
 * count is known — sourced from the client-readable all-time leaderboard mirror
 * (`crownHuntLeaderboardEntries/alltime__{uid}`), reconciled up to the same number
 * the ladder is derived from, NOT the rules-denied `badgeProgress/{uid}`. When the
 * count has not loaded (or the read failed) it falls back to the fixed goal line
 * with no figure; the server note explains the rank itself is tallied server-side.
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
                val nextTierName = ladderName + " " + stringResource(tierNameRes(standing.nextTier))
                val towardNext = standing.crownsTowardNext
                Text(
                    text =
                        if (towardNext != null) {
                            // Honest progress against the fixed goal line, e.g.
                            // "Next rank: Kronjägare Brons — 9 / 10 crowns."
                            stringResource(
                                R.string.crownHunt_statsNextProgress,
                                nextTierName,
                                towardNext.toString(),
                                standing.nextThresholdCrowns.toString(),
                            )
                        } else {
                            // Count not loaded yet — the goal line without a figure.
                            stringResource(
                                R.string.crownHunt_statsNext,
                                nextTierName,
                                standing.nextThresholdCrowns.toString(),
                            )
                        },
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

// ---------------------------------------------------------------------------
// Kronjakt SHOP tab (Crown Hunt Shop, PR2) — buy + view inventory only.
// No deploy/"use" button here; that is a later PR.
// ---------------------------------------------------------------------------

/**
 * The shop tab: KP balance header, the result/error banner for the last buy, and
 * the catalog of buyable perks (each with its owned count and a "Köp" button).
 * Read from [PerkShop.toUiState] — the server-written `config/perkCatalog` mirror
 * for display and the owner-only `perkInventory/{uid}` for counts.
 */
@Composable
private fun ColumnScope.PerkShopContent(
    shopState: PerkShopUiState,
    buyStatus: PerkBuyStatus,
    onBuyPerk: (PerkShopItem) -> Unit,
) {
    when (shopState) {
        PerkShopUiState.Loading ->
            Text(
                text = stringResource(R.string.crownHunt_shopLoading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        PerkShopUiState.Error ->
            Text(
                text = stringResource(R.string.crownHunt_shopError),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

        is PerkShopUiState.Loaded -> {
            PerkBalanceCard(shopState.balanceKp)
            Text(
                text = stringResource(R.string.crownHunt_shopIntro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BuyStatusBanner(buyStatus, shopState.items)
            if (shopState.items.isEmpty()) {
                Text(
                    text = stringResource(R.string.crownHunt_shopEmpty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                shopState.items.forEach { item -> PerkCard(item, buyStatus, onBuyPerk) }
            }
        }
    }
}

/** The member's current KP balance, shown at the top of the shop. */
@Composable
private fun PerkBalanceCard(balanceKp: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.crownHunt_shopBalanceLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.crownHunt_kpValue, balanceKp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * A single perk row: name + family label, blurb, owned count, cost, and the
 * "Köp" button. The button spins and disables while ITS buy is in flight, and is
 * disabled for every row while ANY buy is in flight (the synchronous in-flight
 * guard in [PerkShopCoordinator] backs this up server-safely).
 */
@Composable
private fun PerkCard(
    item: PerkShopItem,
    buyStatus: PerkBuyStatus,
    onBuy: (PerkShopItem) -> Unit,
) {
    val buyingThis =
        buyStatus is PerkBuyStatus.Buying && buyStatus.perkId == item.entry.perkId
    val anyBuying = buyStatus is PerkBuyStatus.Buying
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = perkDisplayName(item.entry.perkId, item.entry.name, item.entry.nameEn),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(perkKindLabelRes(item.entry.kind)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = item.entry.blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // How long the perk's effect lasts once deployed (trap 6h / shield 3h
            // / boost 1h). The blurbs only say "under en period", so spell out the
            // concrete duration here. Singular/plural are two separate localization
            // keys selected in code — the strings.xml files are generated from the
            // localization contracts and the generator emits <string>, not <plurals>.
            val durationHours = perkDurationHours(item.entry.kind)
            val durationRes =
                if (durationHours == 1) {
                    R.string.crownHunt_shopDurationLabelOne
                } else {
                    R.string.crownHunt_shopDurationLabelOther
                }
            Text(
                text = stringResource(durationRes, durationHours),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.crownHunt_kpValue, item.entry.costKp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.crownHunt_shopOwnedLabel, item.ownedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { onBuy(item) },
                    enabled = !anyBuying && item.affordable,
                ) {
                    if (buyingThis) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        // Keep a visible + screen-reader label next to the spinner so
                        // the button isn't left unlabeled while a buy is in flight.
                        Text(
                            text = stringResource(R.string.crownHunt_shopBuying),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    } else {
                        Text(stringResource(R.string.crownHunt_shopBuyButton))
                    }
                }
            }
        }
    }
}

/**
 * The result banner for the most recent buy: a success line (or "already bought"
 * on an idempotent replay), or the reason-specific error message. Idle/Buying
 * render nothing.
 */
@Composable
private fun BuyStatusBanner(buyStatus: PerkBuyStatus, items: List<PerkShopItem>) {
    when (buyStatus) {
        PerkBuyStatus.Idle, is PerkBuyStatus.Buying -> Unit

        is PerkBuyStatus.Bought -> {
            val boughtEntry = items.firstOrNull { it.entry.perkId == buyStatus.perkId }?.entry
            val perkName =
                boughtEntry?.let { perkDisplayName(it.perkId, it.name, it.nameEn) }
                    ?: buyStatus.perkId
            val message =
                if (buyStatus.alreadyPurchased) {
                    stringResource(R.string.crownHunt_shopAlreadyBoughtMessage)
                } else {
                    stringResource(R.string.crownHunt_shopBoughtMessage, perkName)
                }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        is PerkBuyStatus.Failed ->
            Text(
                text = stringResource(buyFailureMessageRes(buyStatus.reason)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
    }
}

/** Resource id for a perk-kind's family label (localized sv/en). */
private fun perkKindLabelRes(kind: PerkKind): Int =
    when (kind) {
        PerkKind.TRAP -> R.string.crownHunt_perkKindTrap
        PerkKind.SHIELD -> R.string.crownHunt_perkKindShield
        PerkKind.BOOST -> R.string.crownHunt_perkKindBoost
    }

/**
 * Display-only effect duration per perk kind, in whole hours, for the shop's
 * "how long it lasts" label. MIRRORS the authoritative server constants in
 * `functions/src/crownHunt/perks-core.ts` (SPIKE_STRIP/trap = 6h, SHIELD = 3h,
 * BOOST = 1h), which are deliberately NOT sent to the client in the perk-catalog
 * display mirror. The server stays the source of truth for enforcement
 * (`expiresAt` is computed server-side); this value is purely the label, so keep
 * it in sync if the server durations ever change.
 */
private fun perkDurationHours(kind: PerkKind): Int =
    when (kind) {
        PerkKind.TRAP -> 6
        PerkKind.SHIELD -> 3
        PerkKind.BOOST -> 1
    }

/** Resource id for a buy-failure reason's message (localized sv/en). */
private fun buyFailureMessageRes(reason: PerkBuyFailureReason): Int =
    when (reason) {
        PerkBuyFailureReason.INSUFFICIENT_FUNDS -> R.string.crownHunt_shopErrorInsufficient
        PerkBuyFailureReason.HOLD_CAP -> R.string.crownHunt_shopErrorHoldCap
        PerkBuyFailureReason.UNAVAILABLE -> R.string.crownHunt_shopErrorUnavailable
        PerkBuyFailureReason.UNKNOWN -> R.string.crownHunt_shopErrorUnknown
    }
