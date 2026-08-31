import XCTest

@testable import KCC

/// Pins the pure shop fold (``PerkShop/items``) and the buy-failure family
/// discrimination — Android's `PerkShop.toUiState` + `perkPurchaseFailedPrecondition`.
final class PerkShopModelTests: XCTestCase {

    private func entry(_ id: String, cost: Int, kind: PerkKind = .trap) -> PerkCatalogEntry {
        PerkCatalogEntry(
            perkId: id, kind: kind, name: id, iconKey: "", costKp: cost, blurb: "", nameEn: ""
        )
    }

    func testItemsResolveOwnedCountsAndAffordability() {
        let result = PerkShop.items(
            catalog: [entry("spike_strip", cost: 150), entry("shield", cost: 100, kind: .shield)],
            inventory: ["spike_strip": 2],
            balanceKp: 120
        )
        XCTAssertEqual(result.balanceKp, 120)
        XCTAssertEqual(result.items.count, 2)
        // spike_strip: owned 2, costs 150 → not affordable at 120.
        XCTAssertEqual(result.items[0].ownedCount, 2)
        XCTAssertFalse(result.items[0].affordable)
        // shield: owned 0 (absent), costs 100 → affordable at 120.
        XCTAssertEqual(result.items[1].ownedCount, 0)
        XCTAssertTrue(result.items[1].affordable)
    }

    func testNilBalanceRendersAsZeroAndNothingAffordable() {
        let result = PerkShop.items(
            catalog: [entry("boost", cost: 1, kind: .boost)],
            inventory: [:],
            balanceKp: nil
        )
        XCTAssertEqual(result.balanceKp, 0)
        XCTAssertFalse(result.items[0].affordable)
    }

    func testNegativeInventoryCountClampsToZero() {
        let result = PerkShop.items(
            catalog: [entry("spike_strip", cost: 10)],
            inventory: ["spike_strip": -5],
            balanceKp: 100
        )
        XCTAssertEqual(result.items[0].ownedCount, 0)
    }

    func testBuyFailureReasonDiscriminator() {
        XCTAssertEqual(PerkPurchaseReason.failure(for: "insufficient_funds"), .insufficientFunds)
        XCTAssertEqual(PerkPurchaseReason.failure(for: "hold_cap_reached"), .holdCap)
        XCTAssertEqual(PerkPurchaseReason.failure(for: "shop_unavailable"), .unavailable)
        XCTAssertEqual(PerkPurchaseReason.failure(for: nil), .unavailable)
    }
}
