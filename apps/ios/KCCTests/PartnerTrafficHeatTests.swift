import Foundation
import XCTest
@testable import KCC

@MainActor
final class PartnerTrafficHeatTests: XCTestCase {
    @MainActor private final class Clock {
        var value = ContinuousClock().now
        func advance(_ seconds: Int) { value = value.advanced(by: .seconds(seconds)) }
    }
    @MainActor private final class Requests {
        var companies: [String] = []
        var pending: [CheckedContinuation<PartnerTrafficHeatSnapshot, Error>] = []
        func load(_ company: String) async throws -> PartnerTrafficHeatSnapshot {
            companies.append(company)
            return try await withCheckedThrowingContinuation { pending.append($0) }
        }
    }

    private func sample(band: Int = 5, expires: Int = 60) -> PartnerTrafficHeatSnapshot {
        PartnerTrafficHeatSnapshot(generatedAt: "2026-11-21T12:00:00Z", windowDays: 90,
            expiresInSeconds: expires, truncated: false, cells: [PartnerHeatArea(
                cellId: "8a0882d9d667fff", latitude: 57.49, longitude: 12.08,
                boundary: [PartnerHeatCoordinate(latitude: 57.49, longitude: 12.08),
                    PartnerHeatCoordinate(latitude: 57.50, longitude: 12.08),
                    PartnerHeatCoordinate(latitude: 57.49, longitude: 12.09)], band: band)])
    }

    private func waitForRequests(_ count: Int, _ requests: Requests) async {
        let deadline = ContinuousClock().now.advanced(by: .seconds(2))
        while requests.pending.count < count, ContinuousClock().now < deadline { await Task.yield() }
        XCTAssertEqual(requests.pending.count, count)
    }

    func testWireResponseDecodesGeometryAndEmptyUnavailableSnapshot() throws {
        let json = #"{"generatedAt":"2026-11-21T12:00:00Z","windowDays":90,"expiresInSeconds":60,"truncated":true,"cells":[{"cellId":"8a0882d9d667fff","latitude":57.49,"longitude":12.08,"band":5,"boundary":[{"latitude":57.49,"longitude":12.08},{"latitude":57.50,"longitude":12.08},{"latitude":57.49,"longitude":12.09}]}]}"#
        let value = try JSONDecoder().decode(PartnerTrafficHeatSnapshot.self, from: Data(json.utf8))
        XCTAssertTrue(value.isValid)
        XCTAssertTrue(value.truncated)
        XCTAssertEqual(value.cells, sample().cells)
        let empty = #"{"generatedAt":null,"windowDays":90,"expiresInSeconds":0,"truncated":false,"cells":[]}"#
        XCTAssertTrue(try JSONDecoder().decode(PartnerTrafficHeatSnapshot.self, from: Data(empty.utf8)).isValid)
    }

    func testMalformedOrOversizedSnapshotsFailClosed() {
        let cell = sample().cells[0]
        let invalid = [
            PartnerTrafficHeatSnapshot(generatedAt: nil, windowDays: 90, expiresInSeconds: 60, truncated: false, cells: [cell]),
            PartnerTrafficHeatSnapshot(generatedAt: "invalid", windowDays: 90, expiresInSeconds: 60, truncated: false, cells: [cell]),
            PartnerTrafficHeatSnapshot(generatedAt: sample().generatedAt, windowDays: 7, expiresInSeconds: 60, truncated: false, cells: [cell]),
            sample(expires: 0), sample(expires: -1), sample(expires: 172801), sample(band: 0), sample(band: 6),
            PartnerTrafficHeatSnapshot(generatedAt: sample().generatedAt, windowDays: 90, expiresInSeconds: 60,
                truncated: false, cells: [cell, cell]),
            PartnerTrafficHeatSnapshot(generatedAt: sample().generatedAt, windowDays: 90, expiresInSeconds: 60,
                truncated: true, cells: (0..<1001).map { index in
                    PartnerHeatArea(cellId: String(format: "%015llx", Int64(index)), latitude: cell.latitude,
                        longitude: cell.longitude, boundary: cell.boundary, band: cell.band)
                })
        ]
        for value in invalid { XCTAssertFalse(value.isValid) }
        for coordinate in [PartnerHeatCoordinate(latitude: .nan, longitude: 0),
                           PartnerHeatCoordinate(latitude: 91, longitude: 0),
                           PartnerHeatCoordinate(latitude: 0, longitude: .infinity),
                           PartnerHeatCoordinate(latitude: 0, longitude: 181)] { XCTAssertFalse(coordinate.isValid) }
        XCTAssertFalse(PartnerHeatArea(cellId: "private-identifier", latitude: 0, longitude: 0, boundary: cell.boundary, band: 1).isValid)
        XCTAssertFalse(PartnerHeatArea(cellId: cell.cellId, latitude: 0, longitude: 0, boundary: [], band: 1).isValid)
    }

    func testRequestStartMonotonicExpiryIncludesNetworkDelay() async {
        let clock = Clock()
        let value = sample()
        let coordinator = PartnerTrafficHeatCoordinator(account: { "manager" }, loader: { _ in
            clock.advance(30)
            return value
        }, now: { clock.value })
        await coordinator.load(companyId: "company")
        XCTAssertNotNil(coordinator.snapshot)
        clock.advance(29)
        coordinator.expireIfNeeded()
        XCTAssertNotNil(coordinator.snapshot)
        clock.advance(1)
        coordinator.expireIfNeeded()
        XCTAssertNil(coordinator.snapshot)
        XCTAssertTrue(coordinator.expired)
    }

    func testSnapshotAlreadyExpiredInTransitIsNeverDisplayed() async {
        let clock = Clock()
        let value = sample()
        let coordinator = PartnerTrafficHeatCoordinator(account: { "manager" }, loader: { _ in
            clock.advance(60)
            return value
        }, now: { clock.value })
        await coordinator.load(companyId: "company")
        XCTAssertNil(coordinator.snapshot)
        XCTAssertTrue(coordinator.expired)
        XCTAssertFalse(coordinator.busy)
    }

    func testSignedOutStateDoesNotMakeARequest() async {
        var reads = 0
        let value = sample()
        let coordinator = PartnerTrafficHeatCoordinator(account: { nil }, loader: { _ in reads += 1; return value })
        await coordinator.load(companyId: "company")
        XCTAssertEqual(reads, 0)
        XCTAssertNil(coordinator.snapshot)
        XCTAssertFalse(coordinator.busy)
    }

    func testRefreshFailureClearsPreviouslyVisibleHeat() async {
        var denied = false
        let value = sample()
        let coordinator = PartnerTrafficHeatCoordinator(account: { "manager" }, loader: { _ in
            if denied { throw KccFunctionsError(code: .permissionDenied) }
            return value
        })
        await coordinator.load(companyId: "company")
        XCTAssertNotNil(coordinator.snapshot)
        denied = true
        await coordinator.load(companyId: "company")
        XCTAssertNil(coordinator.snapshot)
        XCTAssertEqual(coordinator.errorKey, "portal.accessDenied")
        XCTAssertFalse(coordinator.busy)
    }

    func testAccountSwitchDiscardsAnInFlightResponse() async {
        var account = "original"
        let requests = Requests()
        let coordinator = PartnerTrafficHeatCoordinator(account: { account }, loader: { try await requests.load($0) })
        let task = Task { await coordinator.load(companyId: "company") }
        await waitForRequests(1, requests)
        account = "replacement"
        requests.pending[0].resume(returning: sample())
        await task.value
        XCTAssertNil(coordinator.snapshot)
        XCTAssertFalse(coordinator.busy)
    }

    func testNewCompanyLoadWinsOverAnOlderResponse() async {
        let requests = Requests()
        let coordinator = PartnerTrafficHeatCoordinator(account: { "manager" }, loader: { try await requests.load($0) })
        let older = Task { await coordinator.load(companyId: "first-company") }
        await waitForRequests(1, requests)
        let newer = Task { await coordinator.load(companyId: "second-company") }
        await waitForRequests(2, requests)
        let current = sample(band: 3)
        requests.pending[1].resume(returning: current)
        await newer.value
        requests.pending[0].resume(returning: sample(band: 5))
        await older.value
        XCTAssertEqual(requests.companies, ["first-company", "second-company"])
        XCTAssertEqual(coordinator.snapshot, current)
    }

    func testBackgroundClearAndCancellationInvalidatePendingReads() async {
        for cancel in [false, true] {
            let requests = Requests()
            let coordinator = PartnerTrafficHeatCoordinator(account: { "manager" }, loader: { try await requests.load($0) })
            let task = Task { await coordinator.load(companyId: "company") }
            await waitForRequests(1, requests)
            if cancel { task.cancel() } else { coordinator.clear() }
            requests.pending[0].resume(returning: sample())
            await task.value
            XCTAssertNil(coordinator.snapshot)
            XCTAssertNil(coordinator.errorKey)
            XCTAssertFalse(coordinator.busy)
        }
    }

    func testSameScopeRefreshKeepsTheValidMapMountedUntilReplacementArrives() async {
        let value = sample()
        let requests = Requests()
        var reads = 0
        let coordinator = PartnerTrafficHeatCoordinator(account: { "manager" }, loader: { company in
            reads += 1
            if reads == 1 { return value }
            return try await requests.load(company)
        })
        await coordinator.load(companyId: "company")
        let refresh = Task { await coordinator.load(companyId: "company") }
        await waitForRequests(1, requests)
        XCTAssertEqual(coordinator.snapshot, value)
        XCTAssertTrue(coordinator.busy)
        let replacement = sample(band: 3)
        requests.pending[0].resume(returning: replacement)
        await refresh.value
        XCTAssertEqual(coordinator.snapshot, replacement)
        XCTAssertFalse(coordinator.busy)
    }

    func testDeniedPendingRefreshRemovesThePreviouslyVisibleMap() async {
        let value = sample()
        let requests = Requests()
        var reads = 0
        let coordinator = PartnerTrafficHeatCoordinator(account: { "manager" }, loader: { company in
            reads += 1
            if reads == 1 { return value }
            return try await requests.load(company)
        })
        await coordinator.load(companyId: "company")
        let refresh = Task { await coordinator.load(companyId: "company") }
        await waitForRequests(1, requests)
        XCTAssertNotNil(coordinator.snapshot)
        requests.pending[0].resume(throwing: KccFunctionsError(code: .permissionDenied))
        await refresh.value
        XCTAssertNil(coordinator.snapshot)
        XCTAssertEqual(coordinator.errorKey, "portal.accessDenied")
    }

    func testExistingSnapshotExpiresWhileRefreshIsStillPending() async {
        let clock = Clock()
        let value = sample()
        let requests = Requests()
        var reads = 0
        let coordinator = PartnerTrafficHeatCoordinator(account: { "manager" }, loader: { company in
            reads += 1
            if reads == 1 { return value }
            return try await requests.load(company)
        }, now: { clock.value })
        await coordinator.load(companyId: "company")
        clock.advance(30)
        let refresh = Task { await coordinator.load(companyId: "company") }
        await waitForRequests(1, requests)
        XCTAssertNotNil(coordinator.snapshot)
        clock.advance(30)
        // The view runs this tick independently from the awaiting RPC.
        coordinator.expireIfNeeded()
        XCTAssertNil(coordinator.snapshot)
        XCTAssertTrue(coordinator.expired)
        XCTAssertTrue(coordinator.busy)
        requests.pending[0].resume(returning: sample(band: 3))
        await refresh.value
        XCTAssertNotNil(coordinator.snapshot)
        XCTAssertFalse(coordinator.expired)
    }

    func testPreservedSnapshotIsClearedOnAccountChangeWithoutAnAuthListener() async {
        for tickBeforeResponse in [false, true] {
            var account = "original"
            let value = sample()
            let requests = Requests()
            var reads = 0
            let coordinator = PartnerTrafficHeatCoordinator(account: { account }, loader: { company in
                reads += 1
                if reads == 1 { return value }
                return try await requests.load(company)
            })
            await coordinator.load(companyId: "company")
            let refresh = Task { await coordinator.load(companyId: "company") }
            await waitForRequests(1, requests)
            XCTAssertNotNil(coordinator.snapshot)
            account = "replacement"
            if tickBeforeResponse {
                coordinator.expireIfNeeded()
                XCTAssertNil(coordinator.snapshot)
            }
            requests.pending[0].resume(returning: value)
            await refresh.value
            XCTAssertNil(coordinator.snapshot)
            XCTAssertFalse(coordinator.busy)
        }
    }

    func testAnotherCompanyNeverRetainsThePreviousCompanysMapWhileLoading() async {
        let value = sample()
        let requests = Requests()
        var reads = 0
        let coordinator = PartnerTrafficHeatCoordinator(account: { "manager" }, loader: { company in
            reads += 1
            if reads == 1 { return value }
            return try await requests.load(company)
        })
        await coordinator.load(companyId: "first-company")
        let other = Task { await coordinator.load(companyId: "second-company") }
        await waitForRequests(1, requests)
        XCTAssertNil(coordinator.snapshot)
        requests.pending[0].resume(returning: sample(band: 3))
        await other.value
        XCTAssertEqual(coordinator.snapshot?.cells.first?.band, 3)
    }

    func testInvalidServerResponseDoesNotReachTheMap() async {
        let invalid = sample(band: 6)
        let coordinator = PartnerTrafficHeatCoordinator(account: { "manager" }, loader: { _ in invalid })
        await coordinator.load(companyId: "company")
        XCTAssertNil(coordinator.snapshot)
        XCTAssertEqual(coordinator.errorKey, "portal.error")
    }
}
