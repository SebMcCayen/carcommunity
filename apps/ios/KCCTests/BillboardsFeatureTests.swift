import Foundation
import XCTest

@testable import KCC

final class BillboardsFeatureTests: XCTestCase {
    private final class FakeRepository: BillboardsRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: AsyncStream<BillboardsSnapshot>.Continuation?
        private(set) var interactions: [(String, BillboardInteractionType)] = []

        func activeBillboards() -> AsyncStream<BillboardsSnapshot> {
            AsyncStream { continuation in
                lock.withLock { self.continuation = continuation }
            }
        }

        func emit(_ snapshot: BillboardsSnapshot) {
            lock.withLock { continuation }?.yield(snapshot)
        }

        func recordInteraction(billboardId: String, type: BillboardInteractionType) async throws {
            lock.withLock { interactions.append((billboardId, type)) }
        }
    }

    private static func billboard(
        id: String = "billboard-1",
        type: BillboardCallToActionType? = nil,
        value: String? = nil,
        from: Date? = nil,
        until: Date? = nil
    ) -> Billboard {
        Billboard(
            id: id,
            headline: "Local partner",
            message: "Welcome",
            companyId: "company-1",
            latitude: 57.4872,
            longitude: 12.0761,
            callToActionType: type,
            callToActionValue: value,
            availableFrom: from,
            availableUntil: until
        )
    }

    func testVisibilityUsesHalfOpenWindowAndPreservesOrder() {
        let now = Date(timeIntervalSince1970: 1_000)
        let ended = Self.billboard(id: "ended", until: now)
        let live = Self.billboard(id: "live", from: now, until: now.addingTimeInterval(10))
        let future = Self.billboard(id: "future", from: now.addingTimeInterval(5))

        XCTAssertFalse(BillboardVisibility.isVisible(ended, at: now))
        XCTAssertTrue(BillboardVisibility.isVisible(live, at: now))
        XCTAssertEqual(
            BillboardVisibility.visible([ended, live, future], at: now).map(\.id),
            ["live"]
        )
        XCTAssertEqual(
            BillboardVisibility.nextBoundary(after: now, in: [ended, live, future]),
            now.addingTimeInterval(5)
        )
    }

    func testWebsiteActionDefaultsToHTTPSAndRejectsUnsafeSchemes() {
        let schemeless = BillboardCallToAction.resolve(Self.billboard(
            type: .website,
            value: "example.com/offers"
        ))
        XCTAssertEqual(schemeless?.url.absoluteString, "https://example.com/offers")
        XCTAssertEqual(schemeless?.interactionType, .website)

        XCTAssertNil(BillboardCallToAction.resolve(Self.billboard(
            type: .website,
            value: "http://example.com"
        )))
        XCTAssertNil(BillboardCallToAction.resolve(Self.billboard(
            type: .website,
            value: "javascript:alert(1)"
        )))
    }

    func testPhoneActionSanitizesDisplayFormatting() {
        let action = BillboardCallToAction.resolve(Self.billboard(
            type: .phone,
            value: "+46 (0) 300-12 34"
        ))
        XCTAssertEqual(action?.url.absoluteString, "tel:+4603001234")
        XCTAssertEqual(action?.interactionType, .phone)
    }

    @MainActor
    func testCoordinatorPublishesMarkersAndClosesRemovedSelection() async {
        let repository = FakeRepository()
        let now = Date(timeIntervalSince1970: 1_000)
        let coordinator = BillboardsCoordinator(repository: repository, now: { now })
        coordinator.start()
        repository.emit(.loaded([Self.billboard()]))
        await waitUntil { coordinator.markers.count == 1 }

        coordinator.open(billboardId: "billboard-1")
        XCTAssertEqual(coordinator.selectedBillboard?.id, "billboard-1")
        await waitUntil { repository.interactions.count == 1 }
        XCTAssertEqual(repository.interactions.first?.0, "billboard-1")
        XCTAssertEqual(repository.interactions.first?.1, .open)

        repository.emit(.loaded([]))
        await waitUntil { coordinator.markers.isEmpty }
        XCTAssertNil(coordinator.selectedBillboard)
    }

    @MainActor
    func testCoordinatorClearsCachedBillboardsWhenListenerFails() async {
        let repository = FakeRepository()
        let coordinator = BillboardsCoordinator(repository: repository)
        coordinator.start()
        repository.emit(.loaded([Self.billboard()]))
        await waitUntil { coordinator.markers.count == 1 }
        coordinator.open(billboardId: "billboard-1")

        repository.emit(.failed(code: "PERMISSION_DENIED"))
        await waitUntil { coordinator.state == .failed(code: "PERMISSION_DENIED") }

        XCTAssertTrue(coordinator.markers.isEmpty)
        XCTAssertNil(coordinator.selectedBillboard)
    }

    @MainActor
    private func waitUntil(
        timeout: TimeInterval = 2,
        _ predicate: () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate() { return }
            await Task.yield()
            try? await Task.sleep(for: .milliseconds(1))
        }
        XCTFail("Timed out waiting for billboard state")
    }
}
