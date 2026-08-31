import XCTest

@testable import KCC

/// Unit tests for the shop orchestration: the flag/gate/repo trio decides
/// whether the shop is offered at all, the three reads fold into one state, and
/// the buy flow guards against a double-buy and short-circuits an unaffordable
/// tap — Android's `PerkShopCoordinator` + `combineShop`.
final class PerkShopCoordinatorTests: XCTestCase {

    private final class FakeShopRepository: PerkShopRepository, @unchecked Sendable {
        var catalogSnapshot: PerkCatalogSnapshot
        var inventoryMap: [String: Int]
        var buyResult: Result<PerkPurchaseResult, PerkPurchaseError>
        var gateBuys = false
        private(set) var buyCount = 0
        private var gate: CheckedContinuation<Void, Never>?

        init(
            catalog: PerkCatalogSnapshot = .loaded([]),
            inventory: [String: Int] = [:],
            buyResult: Result<PerkPurchaseResult, PerkPurchaseError> = .success(
                PerkPurchaseResult(perkId: "spike_strip", newBalance: 0, inventoryCount: 1, alreadyPurchased: false)
            )
        ) {
            self.catalogSnapshot = catalog
            self.inventoryMap = inventory
            self.buyResult = buyResult
        }

        func catalog() -> AsyncStream<PerkCatalogSnapshot> {
            let snapshot = catalogSnapshot
            return AsyncStream { $0.yield(snapshot); $0.finish() }
        }

        func inventory(uid: String) -> AsyncStream<[String: Int]> {
            let map = inventoryMap
            return AsyncStream { $0.yield(map); $0.finish() }
        }

        func buyPerk(perkId: String, idempotencyKey: String) async throws -> PerkPurchaseResult {
            buyCount += 1
            if gateBuys { await withCheckedContinuation { gate = $0 } }
            switch buyResult {
            case .success(let result): return result
            case .failure(let error): throw error
            }
        }

        func releaseGate() { gate?.resume(); gate = nil }
    }

    private final class FakeBalanceRepository: PerkBalanceRepository, @unchecked Sendable {
        let value: Int?
        init(_ value: Int?) { self.value = value }
        func balance(uid: String) -> AsyncStream<Int?> {
            let value = value
            return AsyncStream { $0.yield(value); $0.finish() }
        }
    }

    private func entry(_ id: String, cost: Int) -> PerkCatalogEntry {
        PerkCatalogEntry(perkId: id, kind: .trap, name: id, iconKey: "", costKp: cost, blurb: "", nameEn: "")
    }

    @MainActor
    private func coordinator(
        repository: FakeShopRepository?,
        balance: Int? = 500,
        perksEnabled: Bool = true,
        uid: String? = "me",
        passesMemberGate: Bool = true
    ) -> PerkShopCoordinator {
        PerkShopCoordinator(
            repository: repository,
            balanceRepository: FakeBalanceRepository(balance),
            uid: uid,
            perksEnabled: perksEnabled,
            passesMemberGate: passesMemberGate,
            keyFactory: { "key" }
        )
    }

    // MARK: - gating

    @MainActor
    func testPerksFlagOffIsUnavailable() {
        let sut = coordinator(repository: FakeShopRepository(), perksEnabled: false)
        XCTAssertFalse(sut.isShopEnabled)
        sut.start()
        XCTAssertEqual(sut.state, .unavailable)
    }

    @MainActor
    func testNilRepositoryIsUnavailable() {
        let sut = coordinator(repository: nil)
        XCTAssertFalse(sut.isShopEnabled)
        sut.start()
        XCTAssertEqual(sut.state, .unavailable)
    }

    @MainActor
    func testGatedOutIsUnavailable() {
        let sut = coordinator(repository: FakeShopRepository(), passesMemberGate: false)
        sut.start()
        XCTAssertEqual(sut.state, .unavailable)
    }

    // MARK: - state fold

    @MainActor
    func testLoadedFoldsCatalogInventoryBalance() async {
        let repository = FakeShopRepository(
            catalog: .loaded([entry("spike_strip", cost: 150)]),
            inventory: ["spike_strip": 2]
        )
        let sut = coordinator(repository: repository, balance: 200)
        sut.start()
        await waitForState(of: sut) {
            if case .loaded(let balanceKp, let items) = $0 {
                return balanceKp == 200 && items.first?.ownedCount == 2 && items.first?.affordable == true
            }
            return false
        }
    }

    @MainActor
    func testEmptyCatalogBecomesEmpty() async {
        let sut = coordinator(repository: FakeShopRepository(catalog: .loaded([])))
        sut.start()
        await waitForState(of: sut) { $0 == .empty }
    }

    @MainActor
    func testCatalogFailureBecomesFailed() async {
        let sut = coordinator(repository: FakeShopRepository(catalog: .failed(code: "PERMISSION_DENIED")))
        sut.start()
        await waitForState(of: sut) { $0 == .failed(code: "PERMISSION_DENIED") }
    }

    /// Regression for the retry-after-failure bug: `start()` used to latch
    /// `started = true` unconditionally, so the failed screen's "try again"
    /// (which calls the coordinator's reload affordance) became a permanent
    /// no-op once the gate had been evaluated once. `reload()` must always
    /// re-subscribe.
    @MainActor
    func testReloadRecoversAfterFailure() async {
        let repository = FakeShopRepository(catalog: .failed(code: "UNAVAILABLE"))
        let sut = coordinator(repository: repository)
        sut.start()
        await waitForState(of: sut) { $0 == .failed(code: "UNAVAILABLE") }

        repository.catalogSnapshot = .loaded([entry("spike_strip", cost: 10)])
        sut.reload()
        await waitForState(of: sut) {
            if case .loaded(_, let items) = $0 { return items.count == 1 }
            return false
        }
    }

    /// `start()` must not latch `started` when the shop is gated off, so a
    /// later `start()` call still re-evaluates the gate rather than being
    /// silently swallowed forever.
    @MainActor
    func testStartDoesNotLatchWhenGatedOff() {
        let sut = coordinator(repository: FakeShopRepository(), perksEnabled: false)
        sut.start()
        XCTAssertEqual(sut.state, .unavailable)
        sut.start()
        XCTAssertEqual(sut.state, .unavailable)
    }

    // MARK: - buy flow

    @MainActor
    func testUnaffordableBuyShortCircuitsWithoutBackend() async {
        let repository = FakeShopRepository(catalog: .loaded([entry("spike_strip", cost: 150)]))
        let sut = coordinator(repository: repository, balance: 10)
        sut.start()
        await sut.buy(perkId: "spike_strip", affordable: false)
        XCTAssertEqual(sut.buyStatus, .failed(perkId: "spike_strip", reason: .insufficientFunds))
        XCTAssertEqual(repository.buyCount, 0)
    }

    @MainActor
    func testSuccessfulBuyBecomesBought() async {
        let repository = FakeShopRepository(
            catalog: .loaded([entry("spike_strip", cost: 150)]),
            buyResult: .success(
                PerkPurchaseResult(perkId: "spike_strip", newBalance: 50, inventoryCount: 3, alreadyPurchased: false)
            )
        )
        let sut = coordinator(repository: repository)
        sut.start()
        await sut.buy(perkId: "spike_strip", affordable: true)
        XCTAssertEqual(
            sut.buyStatus,
            .bought(perkId: "spike_strip", newBalance: 50, inventoryCount: 3, alreadyPurchased: false)
        )
        XCTAssertEqual(repository.buyCount, 1)
    }

    @MainActor
    func testServerRejectionMapsToFailureReason() async {
        let repository = FakeShopRepository(
            catalog: .loaded([entry("spike_strip", cost: 150)]),
            buyResult: .failure(PerkPurchaseError(reason: .holdCap))
        )
        let sut = coordinator(repository: repository)
        sut.start()
        await sut.buy(perkId: "spike_strip", affordable: true)
        XCTAssertEqual(sut.buyStatus, .failed(perkId: "spike_strip", reason: .holdCap))
    }

    @MainActor
    func testDoubleBuyIsGuarded() async {
        let repository = FakeShopRepository(catalog: .loaded([entry("spike_strip", cost: 150)]))
        repository.gateBuys = true
        let sut = coordinator(repository: repository)
        sut.start()

        // First buy suspends at the gate.
        let first = Task { await sut.buy(perkId: "spike_strip", affordable: true) }
        await waitForBuyStatus(of: sut) { if case .buying = $0 { return true }; return false }

        // Second buy while one is in flight is dropped before touching the repo.
        await sut.buy(perkId: "spike_strip", affordable: true)
        XCTAssertEqual(repository.buyCount, 1)

        repository.releaseGate()
        await first.value
        if case .bought = sut.buyStatus {} else { XCTFail("expected bought, got \(sut.buyStatus)") }
    }

    @MainActor
    func testResetClearsTerminalStatus() async {
        let repository = FakeShopRepository(catalog: .loaded([entry("spike_strip", cost: 150)]))
        let sut = coordinator(repository: repository, balance: 10)
        sut.start()
        await sut.buy(perkId: "spike_strip", affordable: false)
        sut.resetBuyStatus()
        XCTAssertEqual(sut.buyStatus, .idle)
    }

    // MARK: - helpers

    @MainActor
    private func waitForState(
        of coordinator: PerkShopCoordinator,
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        until predicate: (PerkShopUiState) -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate(coordinator.state) { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out; last: \(coordinator.state)", file: file, line: line)
    }

    @MainActor
    private func waitForBuyStatus(
        of coordinator: PerkShopCoordinator,
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        until predicate: (PerkBuyStatus) -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate(coordinator.buyStatus) { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out; last: \(coordinator.buyStatus)", file: file, line: line)
    }
}
