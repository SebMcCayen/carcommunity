import XCTest

@testable import KCC

/// Unit tests for the claim-history orchestration: loaded / empty / failed map
/// correctly, and the terminal gates short-circuit to unavailable without a read.
final class CrownHuntClaimsCoordinatorTests: XCTestCase {

    private final class FakeClaimsRepository: CrownHuntClaimsRepository, @unchecked Sendable {
        private let snapshot: CrownClaimsSnapshot
        private(set) var readCount = 0

        init(_ snapshot: CrownClaimsSnapshot) { self.snapshot = snapshot }

        func claims(uid: String) -> AsyncStream<CrownClaimsSnapshot> {
            readCount += 1
            let snapshot = snapshot
            return AsyncStream { continuation in
                continuation.yield(snapshot)
                continuation.finish()
            }
        }
    }

    private func claim(_ id: String, _ result: CrownHuntClaimResult) -> CrownHuntClaim {
        CrownHuntClaim(id: id, pointId: "p", result: result, claimedAt: Date(), pointsAwarded: nil)
    }

    @MainActor
    func testLoadedWithClaims() async {
        let claims = [claim("1", .awarded), claim("2", .movingTooFast)]
        let coordinator = CrownHuntClaimsCoordinator(
            repository: FakeClaimsRepository(.loaded(claims)), uid: "me", passesMemberGate: true
        )
        coordinator.start()
        await waitForState(of: coordinator) { $0 == .loaded(claims) }
    }

    @MainActor
    func testLoadedEmptyBecomesEmpty() async {
        let coordinator = CrownHuntClaimsCoordinator(
            repository: FakeClaimsRepository(.loaded([])), uid: "me", passesMemberGate: true
        )
        coordinator.start()
        await waitForState(of: coordinator) { $0 == .empty }
    }

    @MainActor
    func testFailureCarriesCode() async {
        let coordinator = CrownHuntClaimsCoordinator(
            repository: FakeClaimsRepository(.failed(code: "UNAVAILABLE")),
            uid: "me",
            passesMemberGate: true
        )
        coordinator.start()
        await waitForState(of: coordinator) { $0 == .failed(code: "UNAVAILABLE") }
    }

    @MainActor
    func testNilRepositoryIsUnavailable() {
        let coordinator = CrownHuntClaimsCoordinator(
            repository: nil, uid: "me", passesMemberGate: true
        )
        coordinator.start()
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    @MainActor
    func testGatedOutIsUnavailableWithoutRead() {
        let repository = FakeClaimsRepository(.loaded([claim("1", .awarded)]))
        let coordinator = CrownHuntClaimsCoordinator(
            repository: repository, uid: "me", passesMemberGate: false
        )
        coordinator.start()
        XCTAssertEqual(coordinator.state, .unavailable)
        XCTAssertEqual(repository.readCount, 0)
    }

    @MainActor
    private func waitForState(
        of coordinator: CrownHuntClaimsCoordinator,
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        until predicate: (CrownHuntClaimsUiState) -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate(coordinator.state) { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out; last: \(coordinator.state)", file: file, line: line)
    }
}
