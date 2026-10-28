import XCTest
@testable import KCC

final class AppStoreCatalogTests: XCTestCase {
    func testMonthlyPlansShareOneSubscriptionGroup() {
        XCTAssertTrue(StoreCatalogPolicy.permits([
            entry(plusMonthlyProductID), entry(supporterMonthlyProductID)
        ]))
        XCTAssertFalse(StoreCatalogPolicy.permits([
            entry(plusMonthlyProductID), entry(supporterMonthlyProductID, group: "other")
        ]), "Two separately billed groups must never be offered as mutually exclusive plans")
    }
    func testIncorrectStoreConfigurationCannotBeChargedAsTheMonthlyOffer() {
        for bad in [entry("unknown"), entry(plusMonthlyProductID, auto: false),
                    entry(plusMonthlyProductID, months: 12), entry(plusMonthlyProductID, months: nil),
                    entry(plusMonthlyProductID, group: nil), entry(plusMonthlyProductID, group: "")] {
            XCTAssertFalse(StoreCatalogPolicy.permits([bad]))
        }
    }
    private func entry(_ id: String, auto: Bool = true, months: Int? = 1, group: String? = "membership") -> StoreCatalogEntry {
        StoreCatalogEntry(id: id, isAutoRenewable: auto, monthlyPeriod: months, groupID: group)
    }
}
