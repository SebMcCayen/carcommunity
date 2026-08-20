package com.kungsbackacarcommunity.app.crownhunt

/**
 * Kronjakt SHOP — perk DEPLOY / USE side, pure display model + eligibility
 * (Crown Hunt Shop PR4, UI core).
 *
 * PR1 SOLD perks into `perkInventory`; PR2 rendered the buy/inventory tab; this
 * module is the maths behind USING one from the map's deploy menu: dropping a
 * trap at the caller's GPS, raising a shield, or arming the boost multiplier.
 * It combines the three independent reads the menu needs — owned inventory, the
 * holder's active shield/boost windows, and how many traps are already armed —
 * into one render state and derives, per perk, whether the ACTIVATE action is
 * currently enabled. The `crownHunt-deployPerk` callable (functions
 * `crownHunt/deployPerk.ts`) is the sole authority; this is a DISPLAY hint and
 * an anti-waste guard only — the server re-checks every rule on each deploy.
 *
 * PURE Kotlin (no Firebase, no Compose) so the derivation is JVM-unit-testable,
 * mirroring [PerkShop] and the rest of the crownhunt package.
 *
 * Active-state readability (see firebase/firestore.rules):
 *  - TRAP: the placer may READ their own `activePerks` armed traps, so the
 *    "1 active trap" count and the 1-active-trap cap are authoritative from a
 *    Firestore query.
 *  - SHIELD: `perkShield/{uid}` is backend-only, but the PUBLIC
 *    `perkShieldPublic/{uid}` = { shieldedUntil } is owner-readable, so the
 *    shield countdown comes from that timestamp.
 *  - BOOST: `perkBoost/{uid}` is backend-only with NO public mirror, so there
 *    is no Firestore read for a live boost. Its active window is known only
 *    from the deploy RESULT for the current session (held by the coordinator),
 *    which the menu folds in as [boostActiveUntilMillis].
 */

/**
 * A fully-resolved deploy-menu row: the perk identity, how many the member owns
 * (from `perkInventory/{uid}`), and its current active state. Whether the
 * ACTIVATE action is enabled is [activatable], derived once so the UI and the
 * tests agree on one rule.
 */
data class PerkDeployItem(
    val perkId: String,
    val kind: PerkKind,
    /** Swedish display name (from the catalog entry). */
    val name: String,
    /**
     * English display name (from the catalog entry, empty on an older mirror). Lets
     * the menu's [perkDisplayName] fall back to the catalog's bilingual pair for an
     * unknown/future perk, matching the PerkNames.kt contract; the three known
     * perks resolve via their localized string resource regardless.
     */
    val nameEn: String = "",
    /** Owned units of this perk (0 when absent from the inventory map). */
    val ownedCount: Long,
    /**
     * For SHIELD/BOOST: epoch-ms the current effect expires, or null when it is
     * not active. For TRAP this is always null (a trap's "active" state is the
     * [activeTrapCount], not a single holder-wide window).
     */
    val activeUntilMillis: Long?,
    /**
     * For TRAP: how many of the member's own traps are currently armed (0..).
     * The backend caps this at [MAX_ACTIVE_TRAPS_PER_USER]. Always 0 for
     * SHIELD/BOOST.
     */
    val activeTrapCount: Int,
    /** True when this perk's effect is currently live (shield/boost/trap). */
    val active: Boolean,
    /**
     * True when tapping ACTIVATE should call the backend. False when the member
     * owns none, or the effect is already active (re-raising would waste a unit
     * for no gain — the backend would happily consume one, so the client guards
     * it). A trap is blocked only once the active-trap cap is reached.
     */
    val activatable: Boolean,
)

/** UI-facing state of the whole deploy menu (inventory + active effects). */
sealed interface PerkDeployMenuState {
    data object Loading : PerkDeployMenuState

    data object Error : PerkDeployMenuState

    /**
     * The resolved rows, plus [isEmpty] — true when the member owns NONE of any
     * perk AND has no currently-active effect (no shield, no boost, no armed
     * trap). The rows are always built from the CATALOG (so they can never be
     * literally empty), so the menu leans on [isEmpty] to decide between the
     * "buy some perks first" guidance (the launch first-run case) and the rows.
     */
    data class Loaded(
        val items: List<PerkDeployItem>,
        val isEmpty: Boolean,
    ) : PerkDeployMenuState
}

/**
 * Pure state derivation for the deploy menu. Kept side-effect-free and
 * Firebase-free so it is fully unit-testable.
 *
 * Mirrors the SERVER anti-abuse constant: a member may hold at most one armed
 * trap at a time ([MAX_ACTIVE_TRAPS_PER_USER] in functions `perks-core.ts`).
 * Kept here only as a display/guard hint — the callable re-enforces it in a
 * transaction, so a drift never lets a client actually exceed the cap.
 */
object PerkDeploy {

    /** Client mirror of the backend's `MAX_ACTIVE_TRAPS_PER_USER`. */
    const val MAX_ACTIVE_TRAPS_PER_USER = 1

    /**
     * Folds the catalog + owned inventory + active effect windows into a
     * [PerkDeployMenuState].
     *
     * - A Loading/Error catalog dominates: without the catalog there is nothing
     *   to render, so inventory/active state is irrelevant until it resolves.
     * - Owned counts default to 0 for a perk absent from the inventory map.
     * - A shield/boost is ACTIVE while its expiry is strictly in the future at
     *   [nowMillis]; an expired or null window reads as inactive.
     * - The menu preserves catalog order (spike_strip, shield, boost).
     *
     * @param shieldActiveUntilMillis epoch-ms `perkShieldPublic.shieldedUntil`,
     *   or null when there is no live shield. May also carry the last local
     *   deploy result so the countdown is instant.
     * @param boostActiveUntilMillis epoch-ms of the current session's boost
     *   window (from the deploy result — there is no Firestore read), or null.
     * @param activeTrapCount the member's currently-armed trap count (from the
     *   owner-readable `activePerks` query), clamped to >= 0.
     */
    fun toMenuState(
        catalog: PerkCatalogState,
        inventory: Map<String, Long>,
        shieldActiveUntilMillis: Long?,
        boostActiveUntilMillis: Long?,
        activeTrapCount: Int,
        nowMillis: Long,
    ): PerkDeployMenuState =
        when (catalog) {
            PerkCatalogState.Loading -> PerkDeployMenuState.Loading
            PerkCatalogState.Error -> PerkDeployMenuState.Error
            is PerkCatalogState.Loaded -> {
                val items =
                    catalog.perks.map { entry ->
                        toItem(
                            entry = entry,
                            ownedCount = (inventory[entry.perkId] ?: 0L).coerceAtLeast(0L),
                            shieldActiveUntilMillis = shieldActiveUntilMillis,
                            boostActiveUntilMillis = boostActiveUntilMillis,
                            activeTrapCount = activeTrapCount.coerceAtLeast(0),
                            nowMillis = nowMillis,
                        )
                    }
                PerkDeployMenuState.Loaded(items = items, isEmpty = isNothingToShow(items))
            }
        }

    /**
     * True when there is nothing actionable to present — the member owns none of
     * ANY perk and has no live effect (shield/boost/trap). The rows are always
     * built from the catalog, so this — not `items.isEmpty()` — is what tells the
     * menu to show the "buy some perks first" guidance instead of a wall of
     * disabled rows (the launch first-run experience).
     */
    fun isNothingToShow(items: List<PerkDeployItem>): Boolean =
        items.all { it.ownedCount == 0L && !it.active }

    /**
     * The count of armed traps still live at [nowMillis], from their expiry
     * timestamps. Filtering against a MOVING now (the menu's 15s ticker) rather
     * than the query's attach-time bound means a trap that expires while the menu
     * stays open drops out of the count — re-enabling the trap button without a
     * menu reopen.
     */
    fun liveTrapCount(trapExpiriesMillis: List<Long>, nowMillis: Long): Int =
        trapExpiriesMillis.count { it > nowMillis }

    private fun toItem(
        entry: PerkCatalogEntry,
        ownedCount: Long,
        shieldActiveUntilMillis: Long?,
        boostActiveUntilMillis: Long?,
        activeTrapCount: Int,
        nowMillis: Long,
    ): PerkDeployItem =
        when (entry.kind) {
            PerkKind.TRAP -> {
                val atCap = activeTrapCount >= MAX_ACTIVE_TRAPS_PER_USER
                PerkDeployItem(
                    perkId = entry.perkId,
                    kind = entry.kind,
                    name = entry.name,
                    nameEn = entry.nameEn,
                    ownedCount = ownedCount,
                    activeUntilMillis = null,
                    activeTrapCount = activeTrapCount,
                    active = activeTrapCount > 0,
                    // A trap is deployable while the member owns one AND is below
                    // the 1-active-trap cap. The backend re-checks both.
                    activatable = ownedCount >= 1 && !atCap,
                )
            }
            PerkKind.SHIELD -> {
                val until = shieldActiveUntilMillis
                val active = isActive(until, nowMillis)
                timedItem(entry, ownedCount, if (active) until else null, active)
            }
            PerkKind.BOOST -> {
                val until = boostActiveUntilMillis
                val active = isActive(until, nowMillis)
                timedItem(entry, ownedCount, if (active) until else null, active)
            }
        }

    private fun timedItem(
        entry: PerkCatalogEntry,
        ownedCount: Long,
        activeUntilMillis: Long?,
        active: Boolean,
    ): PerkDeployItem =
        PerkDeployItem(
            perkId = entry.perkId,
            kind = entry.kind,
            name = entry.name,
            nameEn = entry.nameEn,
            ownedCount = ownedCount,
            activeUntilMillis = activeUntilMillis,
            activeTrapCount = 0,
            active = active,
            // Deployable while owned AND not already active — re-raising an
            // active shield/boost would burn a unit for no benefit.
            activatable = ownedCount >= 1 && !active,
        )

    /** True while [expiresAtMillis] is strictly in the future at [nowMillis]. */
    fun isActive(expiresAtMillis: Long?, nowMillis: Long): Boolean =
        expiresAtMillis != null && expiresAtMillis > nowMillis

    /**
     * Whole-minutes-rounded-UP remaining time until [expiresAtMillis], for a
     * coarse countdown ("2h 59m kvar"). Returns 0 when already expired/null.
     * Rounding UP means a shield with 1 second left still reads "1m", never a
     * live effect shown as "0m".
     */
    fun remainingMinutes(expiresAtMillis: Long?, nowMillis: Long): Long {
        if (expiresAtMillis == null) return 0L
        val remainingMs = expiresAtMillis - nowMillis
        if (remainingMs <= 0L) return 0L
        return (remainingMs + 59_999L) / 60_000L
    }
}
