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
    func testIncompleteCatalogueCannotOfferAnUnverifiedSubscriptionSwitch() {
        for entries in [[], [entry(plusMonthlyProductID)], [entry(supporterMonthlyProductID)],
                        [entry(plusMonthlyProductID), entry(plusMonthlyProductID)]] {
            XCTAssertFalse(StoreCatalogPolicy.permits(entries))
        }
    }
    func testIncorrectStoreConfigurationCannotBeChargedAsTheMonthlyOffer() {
        for bad in [entry("unknown"), entry(plusMonthlyProductID, auto: false),
                    entry(plusMonthlyProductID, months: 12), entry(plusMonthlyProductID, months: nil),
                    entry(plusMonthlyProductID, group: nil), entry(plusMonthlyProductID, group: "")] {
            XCTAssertFalse(StoreCatalogPolicy.permits([bad]))
        }
    }
    func testReleaseDisclosuresResolveFromTheirDedicatedTableInBothLanguages() throws {
        for language in ["sv", "en"] {
            let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"))
            let bundle = try XCTUnwrap(Bundle(path: path))
            for key in ["iosStore.manage", "iosStore.renewal", "iosStore.deletionBilling", "iosStore.signedOutPrivacy"] {
                let text = bundle.localizedString(forKey: key, value: nil, table: "AppStore")
                XCTAssertNotEqual(text, key, "Missing \(language) release disclosure")
            }
            let price = bundle.localizedString(forKey: "iosStore.monthlyPrice", value: nil, table: "AppStore")
            XCTAssertTrue(String(format: price, "TEST_PRICE").contains("TEST_PRICE"))
        }
    }

    private func entry(_ id: String, auto: Bool = true, months: Int? = 1, group: String? = "membership") -> StoreCatalogEntry {
        StoreCatalogEntry(id: id, isAutoRenewable: auto, monthlyPeriod: months, groupID: group)
    }
}
