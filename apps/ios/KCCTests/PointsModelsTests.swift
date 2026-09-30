import XCTest

@testable import KCC

final class PointsModelsTests: XCTestCase {
    func testEntryDecodesContractFields() throws {
        let date = Date(timeIntervalSince1970: 100)
        let entry = try XCTUnwrap(PointsEntry.fromMap(id: "credit", map: [
            "amount": NSNumber(value: 25),
            "balanceAfter": NSNumber(value: 125),
            "description": "Badge unlocked",
            "createdAt": date,
        ]))
        XCTAssertEqual(entry.id, "credit")
        XCTAssertEqual(entry.amount, 25)
        XCTAssertEqual(entry.balanceAfter, 125)
        XCTAssertEqual(entry.description, "Badge unlocked")
        XCTAssertEqual(entry.createdAt, date)
    }

    func testEntryRequiresNumericAmountAndRejectsBooleanBridge() {
        XCTAssertNil(PointsEntry.fromMap(id: "missing", map: [:]))
        XCTAssertNil(PointsEntry.fromMap(id: "bool", map: ["amount": NSNumber(value: true)]))
    }

    func testSortedForListIsNewestFirstWithUndatedLast() {
        let entries = [
            entry("a", amount: 10, time: 100),
            entry("b", amount: -5, time: nil),
            entry("c", amount: 20, time: 300),
            entry("d", amount: 5, time: 200),
        ]
        XCTAssertEqual(Points.sortedForList(entries).map(\.id), ["c", "d", "a", "b"])
    }

    func testRecentEarningsKeepsCreditsOnlyNewestFirstAndCaps() {
        let entries = [
            entry("old", amount: 5, time: 100),
            entry("spend", amount: -50, time: 500),
            entry("newest", amount: 25, time: 400),
            entry("mid", amount: 20, time: 300),
            entry("zero", amount: 0, time: 350),
        ]
        XCTAssertEqual(Points.recentEarnings(entries).map(\.id), ["newest", "mid", "old"])
        XCTAssertEqual(Points.recentEarnings(entries, limit: 2).map(\.id), ["newest", "mid"])
        XCTAssertTrue(Points.recentEarnings(entries, limit: 0).isEmpty)
    }

    private func entry(_ id: String, amount: Int64, time: TimeInterval?) -> PointsEntry {
        PointsEntry(
            id: id,
            amount: amount,
            balanceAfter: nil,
            description: id,
            createdAt: time.map(Date.init(timeIntervalSince1970:))
        )
    }
}
