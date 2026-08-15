package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kungsbackacarcommunity.app.badges.BadgesRepository
import com.kungsbackacarcommunity.app.badges.BadgesState
import com.kungsbackacarcommunity.app.points.PointsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Kronjakt hub route: wires the read-only stats/leaderboard stream and the
 * member's own badge standing into [CrownHuntScreen], plus — when the
 * `crownHuntPerks` flag is on — the SHOP (buy + view-inventory) surface.
 *
 * The hub itself no longer wires a claim path (crowns are collected on the map).
 * It is a pure read of the #710 aggregates via [statsRepository] and the badge
 * ladder via [badgesRepository]; the shop adds the `config/perkCatalog` display
 * mirror + owner-only `perkInventory/{uid}` reads and the buy callable, all via
 * [perkShopRepository].
 *
 * @param perksEnabled the `crownHuntPerks` flag (contract default FALSE). While
 *   false the shop tab is never rendered and none of the shop flows are
 *   subscribed — the page is the pre-shop hub, so the whole shop ships dark.
 * @param perkShopRepository catalog/inventory/buy source. Null in a config-less
 *   build → the shop tab (if the flag is on) shows its loading state.
 * @param pointsRepository the member's KP balance source (the same owner-scoped
 *   `pointsLedger/{uid}` listener the Points wallet uses — no new query shape).
 */
@Composable
fun CrownHuntRoute(
    statsRepository: CrownHuntStatsRepository?,
    passesMemberGate: Boolean,
    onBack: () -> Unit,
    badgesRepository: BadgesRepository? = null,
    uid: String? = null,
    perksEnabled: Boolean = false,
    perkShopRepository: PerkShopRepository? = null,
    pointsRepository: PointsRepository? = null,
) {
    val statsState by
        remember(statsRepository, uid, passesMemberGate) {
            if (statsRepository != null && uid != null && passesMemberGate) {
                statsRepository.observeStats(uid)
            } else {
                flowOf(CrownStatsUiState.Loading)
            }
        }
            .collectAsState(initial = CrownStatsUiState.Loading)

    // The member's own crown-hunter badge standing. Only subscribed when the
    // member gate passes (a non-member sees the teaser) and a repo + uid are
    // wired; otherwise it stays null and the badge band is omitted.
    val badgesState by
        remember(badgesRepository, uid, passesMemberGate) {
            if (badgesRepository != null && uid != null && passesMemberGate) {
                badgesRepository.observeBadges(uid)
            } else {
                flowOf(BadgesState.Loading)
            }
        }
            .collectAsState(initial = BadgesState.Loading)
    val kronjagare =
        remember(badgesState) {
            (badgesState as? BadgesState.Loaded)?.let { CrownHuntStats.kronjagare(it.badges) }
        }

    // SHOP. Only wired when the flag is on, the gate passes and the repos + uid
    // exist — otherwise every shop flow is a single Loading emission and nothing
    // subscribes to the catalog/inventory/ledger. Combines the three independent
    // reads into one render state through the pure [PerkShop.toUiState].
    val shopEnabled =
        perksEnabled && perkShopRepository != null && pointsRepository != null &&
            uid != null && passesMemberGate
    val shopState by
        remember(shopEnabled, perkShopRepository, pointsRepository, uid) {
            if (shopEnabled) {
                combineShop(perkShopRepository!!, pointsRepository!!, uid!!)
            } else {
                flowOf(PerkShopUiState.Loading)
            }
        }
            .collectAsState(initial = PerkShopUiState.Loading)

    val coordinator =
        remember(perkShopRepository) { perkShopRepository?.let { PerkShopCoordinator(it) } }
    val buyStatus: PerkBuyStatus =
        coordinator?.status?.collectAsState()?.value ?: PerkBuyStatus.Idle
    val scope = rememberCoroutineScope()

    CrownHuntScreen(
        statsState = statsState,
        passesMemberGate = passesMemberGate,
        onBack = onBack,
        kronjagare = kronjagare,
        perksEnabled = perksEnabled,
        shopState = shopState,
        buyStatus = buyStatus,
        onBuyPerk = { item ->
            coordinator?.let { active ->
                scope.launch { active.buy(item.entry.perkId, item.affordable) }
            }
        },
    )
}

/** Combines the catalog + inventory + KP balance into one shop render state. */
private fun combineShop(
    perkShopRepository: PerkShopRepository,
    pointsRepository: PointsRepository,
    uid: String,
): Flow<PerkShopUiState> =
    combine(
        perkShopRepository.observeCatalog(),
        perkShopRepository.observeInventory(uid),
        // Emit null first so the shop still renders (catalog/inventory) even if the
        // balance listener errors on its first snapshot and never emits — otherwise
        // combine() would hang the whole shop in Loading forever. null renders as 0 KP.
        pointsRepository.observeBalance(uid).onStart { emit(null) },
    ) { catalog, inventory, balance ->
        PerkShop.toUiState(catalog, inventory, balance)
    }
