import Foundation

/// Reads the Kronjakt shop's DISPLAY catalog + the member's owned inventory and
/// runs the buy — the iOS port of Android's `PerkShopRepository`, restricted to
/// the buy + view-inventory surface (deploy/"use" is a later PR).
///
/// Firebase-free protocol so the coordinator/screen are testable with a fake.
/// The one implementation (``FirebasePerkShopRepository``) is:
///  - a member-readable LIVE listener on `config/perkCatalog` (rules gate
///    `isActiveMember()`),
///  - an owner-only LIVE single-doc listener on `perkInventory/{uid}` (rules
///    gate `get` if owner), and
///  - the `crownHunt-buyPerk` callable (europe-west1).
protocol PerkShopRepository: AnyObject, Sendable {
    /// The LIVE `config/perkCatalog` display mirror; keeps last-known on a
    /// transient error (only a failure BEFORE the first successful read
    /// surfaces as ``PerkCatalogSnapshot/failed(code:)``).
    func catalog() -> AsyncStream<PerkCatalogSnapshot>

    /// The LIVE owned-perk counts from `perkInventory/{uid}` as a
    /// `{ perkId: count }` map (empty when the doc is absent). Emits an empty
    /// map on a first-snapshot error so the shop still renders, and keeps the
    /// last-known counts on a transient error after a successful load.
    func inventory(uid: String) -> AsyncStream<[String: Int]>

    /// Buys ONE unit of `perkId` via `crownHunt-buyPerk`. `idempotencyKey`
    /// makes a retried call a no-op that debits once.
    /// - Throws: ``PerkPurchaseError`` carrying the mapped failure family; the
    ///   SDK error is translated at the seam and never propagates.
    func buyPerk(perkId: String, idempotencyKey: String) async throws -> PerkPurchaseResult
}
