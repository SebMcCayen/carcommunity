package com.kungsbackacarcommunity.app.crownhunt

/**
 * Kronjakt SHOP — pure display model + mapper (Crown Hunt Shop PR2, UI core).
 *
 * The shop is the FIRST member-facing Kronpoäng SINK: a member spends Kronpoäng
 * (KP) to buy a perk, which lands in their backend-only `perkInventory`. This PR
 * builds the BUY + VIEW-INVENTORY surface only — no deploy/"use" button (that is
 * a later PR). Everything is gated on the contract-default-OFF `crownHuntPerks`
 * flag, so the whole tab is invisible until an operator turns it on.
 *
 * This module is PURE Kotlin (no Firebase, no Compose) so the state derivation
 * is JVM-unit-testable, mirroring the rest of the crownhunt package
 * (CrownHuntStats, CrownSpawnQuery, …). The authoritative costs/effects live on
 * the server (functions perks-core.ts); the client only ever RENDERS what the
 * server-written `config/perkCatalog` mirror carries and never trusts a price it
 * computes locally — a "Köp" tap sends only the perkId, and the callable derives
 * the KP to debit from its own constants.
 */

/**
 * The perk family, mirrored from the server catalog's `kind`. Drives only the
 * display label in this PR (the activation path each kind takes is a later PR).
 * An unrecognised wire value maps to null so a drifted catalog entry is dropped
 * rather than mislabelled.
 */
enum class PerkKind {
    TRAP,
    SHIELD,
    BOOST,
    ;

    companion object {
        fun fromWire(value: String?): PerkKind? =
            when (value) {
                "trap" -> TRAP
                "shield" -> SHIELD
                "boost" -> BOOST
                else -> null
            }
    }
}

/**
 * One entry of the member-readable `config/perkCatalog` DISPLAY MIRROR — exactly
 * the fields the shop renders. Effect parameters (radius/drain/duration/
 * multiplier) are deliberately NOT mirrored and never reach the client.
 */
data class PerkCatalogEntry(
    val perkId: String,
    val kind: PerkKind,
    /** Swedish display name (the mirror's `name`). */
    val name: String,
    val iconKey: String,
    val costKp: Long,
    val blurb: String,
    /**
     * English display name (the mirror's `nameEn`, catalog doc version >= 2).
     * Empty on an older mirror; the UI then falls back to the localized per-perk
     * string resource for the known perks or the Swedish [name]. Appended (with a
     * default) so existing positional constructions stay valid.
     */
    val nameEn: String = "",
)

/** UI-facing state of the perk catalog listener. */
sealed interface PerkCatalogState {
    data object Loading : PerkCatalogState

    data object Error : PerkCatalogState

    data class Loaded(val perks: List<PerkCatalogEntry>) : PerkCatalogState
}

/**
 * A fully-resolved shop row: the catalog entry, how many the member already owns
 * (from `perkInventory/{uid}`), and whether their current KP balance can afford
 * one unit. `affordable` is a DISPLAY hint only — the server re-checks the
 * balance on every buy and remains the sole authority on whether a debit lands.
 */
data class PerkShopItem(
    val entry: PerkCatalogEntry,
    val ownedCount: Long,
    val affordable: Boolean,
)

/** UI-facing state of the whole shop tab (catalog + inventory + balance). */
sealed interface PerkShopUiState {
    data object Loading : PerkShopUiState

    data object Error : PerkShopUiState

    data class Loaded(
        /** The member's current KP balance (0 until the first ledger read). */
        val balanceKp: Long,
        val items: List<PerkShopItem>,
    ) : PerkShopUiState
}

/**
 * Pure state derivation for the shop tab. Combines the three independent reads —
 * the catalog listener state, the owned-inventory map and the KP balance — into
 * a single render state. Kept side-effect-free and Firebase-free so it is fully
 * unit-testable.
 */
object PerkShop {

    /**
     * Folds the catalog/inventory/balance into a [PerkShopUiState].
     *
     * - A Loading/Error catalog dominates: without the catalog there is nothing
     *   to render, so inventory/balance are irrelevant until it resolves.
     * - A null balance (no ledger read yet) renders as 0 KP, and every perk is
     *   then simply shown as not-yet-affordable rather than blocking the list.
     * - Owned counts default to 0 for a perk absent from the inventory map.
     */
    fun toUiState(
        catalog: PerkCatalogState,
        inventory: Map<String, Long>,
        balanceKp: Long?,
    ): PerkShopUiState =
        when (catalog) {
            PerkCatalogState.Loading -> PerkShopUiState.Loading
            PerkCatalogState.Error -> PerkShopUiState.Error
            is PerkCatalogState.Loaded -> {
                val balance = balanceKp ?: 0L
                PerkShopUiState.Loaded(
                    balanceKp = balance,
                    items =
                        catalog.perks.map { entry ->
                            PerkShopItem(
                                entry = entry,
                                ownedCount = (inventory[entry.perkId] ?: 0L).coerceAtLeast(0L),
                                affordable = balance >= entry.costKp,
                            )
                        },
                )
            }
        }
}
