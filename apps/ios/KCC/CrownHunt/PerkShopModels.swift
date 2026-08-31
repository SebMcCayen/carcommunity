import Foundation

/// Kronjakt SHOP — pure display model + fold, the iOS port of Android's
/// `PerkShop.kt`.
///
/// The shop is the first member-facing Crown-Point SINK: a member spends Crown
/// Points to buy a perk, which lands in their backend-only `perkInventory`.
/// This slice builds the BUY + VIEW-INVENTORY surface only — no deploy/"use"
/// button (a later PR). Everything is gated on the contract-default-OFF
/// `crownHuntPerks` flag, so the whole tab is invisible until an operator turns
/// it on (see ``CrownHuntFeatureFlags``).
///
/// Pure Swift so the state derivation is unit-testable. The authoritative
/// costs/effects live on the server; the client only RENDERS what the
/// server-written `config/perkCatalog` mirror carries and never trusts a price
/// it computes locally — a "Buy" tap sends only the perkId, and the callable
/// derives the Crown Points to debit from its own constants.

/// One entry of the member-readable `config/perkCatalog` DISPLAY MIRROR —
/// exactly the fields the shop renders. Effect parameters (radius/drain/
/// duration/multiplier) are deliberately NOT mirrored and never reach the
/// client. Android's `PerkCatalogEntry`.
struct PerkCatalogEntry: Equatable, Sendable, Identifiable {
    let perkId: String
    let kind: PerkKind
    /// Swedish display name (the mirror's `name`).
    let name: String
    let iconKey: String
    let costKp: Int
    let blurb: String
    /// English display name (the mirror's `nameEn`, catalog doc version >= 2).
    /// Empty on an older mirror; the UI then falls back to the localized
    /// per-perk string for the known perks or the Swedish ``name``.
    let nameEn: String

    var id: String { perkId }
}

/// A fully-resolved shop row: the catalog entry, how many the member already
/// owns (from `perkInventory/{uid}`), and whether their current Crown Point
/// balance can afford one unit. `affordable` is a DISPLAY hint only — the
/// server re-checks the balance on every buy. Android's `PerkShopItem`.
struct PerkShopItem: Equatable, Sendable, Identifiable {
    let entry: PerkCatalogEntry
    let ownedCount: Int
    let affordable: Bool

    var id: String { entry.perkId }
}

/// The catalog listener's settled emission — Android's `PerkCatalogState`
/// minus its Loading case (a repository stream only emits SETTLED results; the
/// coordinator supplies loading before the first emission, the same split the
/// events feature uses).
enum PerkCatalogSnapshot: Equatable, Sendable {
    /// The listener failed before any successful read. `code` is the bare
    /// Firestore status name when one was available (PII-safe).
    case failed(code: String?)
    /// The catalog mirror's perks (empty when the doc is absent/malformed).
    case loaded([PerkCatalogEntry])
}

/// Pure state derivation for the shop tab — Android's `PerkShop.toUiState`.
enum PerkShop {
    /// Folds the catalog/inventory/balance into the shop's rows + balance.
    ///
    /// - A null balance (no ledger read yet) renders as 0 CP, and every perk is
    ///   then simply shown as not-yet-affordable rather than blocking the list.
    /// - Owned counts default to 0 for a perk absent from the inventory map.
    static func items(
        catalog: [PerkCatalogEntry],
        inventory: [String: Int],
        balanceKp: Int?
    ) -> (balanceKp: Int, items: [PerkShopItem]) {
        let balance = balanceKp ?? 0
        let items = catalog.map { entry in
            PerkShopItem(
                entry: entry,
                ownedCount: max(0, inventory[entry.perkId] ?? 0),
                affordable: balance >= entry.costKp
            )
        }
        return (balance, items)
    }
}

// MARK: - Buy outcome + rejection families

/// Outcome of a successful `crownHunt-buyPerk` call — Android's
/// `PerkPurchaseResult`, narrowed to what the coordinator renders.
struct PerkPurchaseResult: Equatable, Sendable {
    let perkId: String
    /// Crown Points balance after the debit.
    let newBalance: Int
    /// The buyer's count of this perk AFTER the grant.
    let inventoryCount: Int
    /// True when an idempotent replay returned the original purchase.
    let alreadyPurchased: Bool
}

/// Why a buy failed — selects the localized message the shop shows. Mirrors
/// Android's `PerkBuyFailureReason`, told apart by the server's structured
/// `details.reason` discriminator rather than a localizable message.
enum PerkBuyFailureReason: Equatable, Sendable {
    /// The member cannot afford the perk (client pre-check or server overdraft).
    case insufficientFunds
    /// The member already holds the max of this perk (per-perk or total value).
    case holdCap
    /// The shop is off, the account cannot spend, or the perk is unknown.
    case unavailable
    /// Anything else (network, unexpected server error).
    case unknown
}

/// A `buyPerk` rejection, carrying the mapped ``PerkBuyFailureReason``. The
/// repository translates the callable's `details.reason` at the seam so the
/// coordinator stays Firebase-free (the same PII-safe rule as the events
/// slice: the caller branches on a code, never on the SDK message).
struct PerkPurchaseError: Error, Equatable, Sendable {
    let reason: PerkBuyFailureReason
}

/// The wire values of the backend's `details.reason` discriminator — mirror
/// the constants in functions `crownHunt/perks-core.ts`.
enum PerkPurchaseReason {
    static let insufficientFunds = "insufficient_funds"
    static let holdCap = "hold_cap_reached"

    /// Maps a `failed-precondition` reason discriminator to its failure family.
    /// `insufficient_funds` → ``PerkBuyFailureReason/insufficientFunds``;
    /// `hold_cap_reached` → ``PerkBuyFailureReason/holdCap``; any other reason
    /// (including none) → ``PerkBuyFailureReason/unavailable``.
    static func failure(for reason: String?) -> PerkBuyFailureReason {
        switch reason {
        case insufficientFunds: return .insufficientFunds
        case holdCap: return .holdCap
        default: return .unavailable
        }
    }
}
