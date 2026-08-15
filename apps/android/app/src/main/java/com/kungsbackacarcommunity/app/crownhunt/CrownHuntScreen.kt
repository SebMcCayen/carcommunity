package com.kungsbackacarcommunity.app.crownhunt

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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * @param perksEnabled the `crownHuntPerks` flag. DEFAULT FALSE, and load-bearing:
 *   when off (the shipped default) the shop tab is NOT rendered at all — the page
 *   is byte-identical to the pre-shop hub. Only when an operator switches the flag
 *   on does the "Butik" tab appear, so the whole shop ships dark.
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
    shopState: PerkShopUiState = PerkShopUiState.Loading,
    buyStatus: PerkBuyStatus = PerkBuyStatus.Idle,
    onBuyPerk: (PerkShopItem) -> Unit = {},
) {
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
            CrownHuntHubContent(statsState, kronjagare)
            return@AeroPage
        }

        // Flag ON: a two-tab hub — the read-only stats/legend home, and the shop.
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
            else -> CrownHuntHubContent(statsState, kronjagare)
        }
    }
}

/**
 * The read-only Kronjakt HOME: the member's badge standing, this season's stats +
 * leaderboard, and the crown legend. Extracted so it renders identically whether
 * or not the shop tab bar is present above it.
 */
@Composable
private fun ColumnScope.CrownHuntHubContent(
    statsState: CrownStatsUiState,
    kronjagare: KronjagareStanding?,
) {
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

    // Educational legend: the crown types and their disappear rules, so
    // testers stop asking "if I collect one, does it disappear afterwards?".
    // Read-only copy; grounded in the two claim paths (submitClaim /
    // claimSpawn) and the rarity table.
    CrownLegendCard()
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
                    text = item.entry.name,
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
            val perkName =
                items.firstOrNull { it.entry.perkId == buyStatus.perkId }?.entry?.name
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

/** Swedish family label for a perk kind. */
private fun perkKindLabelRes(kind: PerkKind): Int =
    when (kind) {
        PerkKind.TRAP -> R.string.crownHunt_perkKindTrap
        PerkKind.SHIELD -> R.string.crownHunt_perkKindShield
        PerkKind.BOOST -> R.string.crownHunt_perkKindBoost
    }

/** The message string for a buy-failure reason. */
private fun buyFailureMessageRes(reason: PerkBuyFailureReason): Int =
    when (reason) {
        PerkBuyFailureReason.INSUFFICIENT_FUNDS -> R.string.crownHunt_shopErrorInsufficient
        PerkBuyFailureReason.UNAVAILABLE -> R.string.crownHunt_shopErrorUnavailable
        PerkBuyFailureReason.UNKNOWN -> R.string.crownHunt_shopErrorUnknown
    }
