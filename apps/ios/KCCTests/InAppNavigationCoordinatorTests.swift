import XCTest
import UIKit
@testable import KCC

@MainActor
final class InAppNavigationCoordinatorTests: XCTestCase {
    private final class Session: InAppGuidanceSession {
        let viewController = UIViewController()
        var stops = 0
        func stop() { stops += 1 }
    }
    private final class Service: InAppNavigationService {
        var isAvailable = true
        var origins: [LocationFix] = []
        var destinations: [PlaceSuggestion] = []
        let session = Session()
        var suspended: CheckedContinuation<any InAppGuidanceSession, any Error>?
        var suspend = false
        var fails = false
        func route(origin: LocationFix, destination: PlaceSuggestion,
                   onFinish: @escaping @MainActor () -> Void) async throws -> any InAppGuidanceSession {
            origins.append(origin); destinations.append(destination)
            if suspend { return try await withCheckedThrowingContinuation { suspended = $0 } }
            if fails { throw URLError(.notConnectedToInternet) }
            return session
        }
    }
    private var destination: PlaceSuggestion {
        PlaceSuggestion(id: "station", name: "Station", address: "Address",
                        point: MapPoint(longitude: 12.249, latitude: 57.109))
    }
    private func fix(age: TimeInterval = 0, accuracy: Double? = 5) -> LocationFix {
        LocationFix.of(latitude: 57.489, longitude: 12.076,
                       timestamp: Date().addingTimeInterval(-age), accuracyMeters: accuracy)!
    }
    private func wait(_ predicate: () -> Bool) async {
        for _ in 0..<2000 {
            if predicate() { return }
            try? await Task.sleep(for: .milliseconds(1))
        }
        XCTFail("Timed out waiting for navigation state")
    }
    func testConstructingNavigationDoesNotStartLocationOrRouting() {
        let location = StubLocationProvider(authorization: .whileInUse), service = Service()
        let coordinator = InAppNavigationCoordinator(location: location, service: service)
        XCTAssertEqual(coordinator.phase, .idle)
        XCTAssertEqual(location.activeFixStreamCount, 0)
        XCTAssertTrue(service.origins.isEmpty)
    }
    func testUnavailableSDKAndDeniedLocationNeverMakeARouteRequest() {
        for authorized in [false, true] {
            let location = StubLocationProvider(authorization: authorized ? .whileInUse : .denied), service = Service()
            service.isAvailable = !authorized
            let coordinator = InAppNavigationCoordinator(location: location, service: service)
            coordinator.start(destination) {}
            XCTAssertEqual(coordinator.phase, authorized ? .unavailable : .locationDenied)
            XCTAssertEqual(location.activeFixStreamCount, 0)
            XCTAssertTrue(service.origins.isEmpty)
        }
    }
    func testInvalidDestinationNeverCreatesAProviderOrLocationSubscription() {
        let location = StubLocationProvider(authorization: .whileInUse), service = Service()
        let coordinator = InAppNavigationCoordinator(location: location, service: service)
        coordinator.start(PlaceSuggestion(id: "bad", name: "Bad", address: nil,
            point: MapPoint(longitude: .nan, latitude: 0))) {}
        XCTAssertEqual(coordinator.phase, .unavailable)
        XCTAssertEqual(location.activeFixStreamCount, 0)
        XCTAssertTrue(service.origins.isEmpty)
    }
    func testOnlyARecentAccurateOriginCanStartGuidanceToTheSelectedDestination() async {
        let location = StubLocationProvider(authorization: .whileInUse), service = Service()
        let coordinator = InAppNavigationCoordinator(location: location, service: service)
        coordinator.start(destination) {}
        location.emitFix(fix(age: 120))
        location.emitFix(fix(accuracy: 500))
        location.emitFix(fix(accuracy: nil))
        await Task.yield()
        XCTAssertTrue(service.origins.isEmpty)
        let current = fix()
        location.emitFix(current)
        await wait { coordinator.phase == .guiding }
        XCTAssertEqual(service.origins, [current])
        XCTAssertEqual(service.destinations, [destination])
        await wait { location.activeFixStreamCount == 0 }
        coordinator.stop()
        XCTAssertEqual(service.session.stops, 1)
        XCTAssertNil(coordinator.session)
    }
    func testCancelWhileLocatingReleasesGPSWithoutStartingATrip() async {
        let location = StubLocationProvider(authorization: .whileInUse), service = Service()
        let coordinator = InAppNavigationCoordinator(location: location, service: service)
        coordinator.start(destination) {}
        coordinator.stop()
        await wait { location.activeFixStreamCount == 0 }
        XCTAssertEqual(coordinator.phase, .idle)
        XCTAssertTrue(service.origins.isEmpty)
    }
    func testPermissionRevocationAndTimeoutFailWithoutRouting() async {
        for revoke in [true, false] {
            let location = StubLocationProvider(authorization: .whileInUse), service = Service()
            let coordinator = InAppNavigationCoordinator(location: location, service: service, locationTimeout: .milliseconds(20))
            coordinator.start(destination) {}
            if revoke { location.setAuthorization(.denied) }
            await wait { coordinator.phase == (revoke ? .locationDenied : .locationUnavailable) }
            await wait { location.activeFixStreamCount == 0 }
            XCTAssertTrue(service.origins.isEmpty)
            coordinator.stop()
        }
    }
    func testACancelledLateRouteIsStoppedInsteadOfReopeningNavigation() async {
        let location = StubLocationProvider(authorization: .whileInUse), service = Service()
        service.suspend = true
        let coordinator = InAppNavigationCoordinator(location: location, service: service)
        coordinator.start(destination) {}
        location.emitFix(fix())
        await wait { service.suspended != nil }
        coordinator.stop()
        service.suspended?.resume(returning: service.session); service.suspended = nil
        await wait { service.session.stops == 1 }
        XCTAssertEqual(coordinator.phase, .idle)
        XCTAssertNil(coordinator.session)
    }
    func testRouteFailureOffersRetryWithoutLeavingTheApp() async {
        let location = StubLocationProvider(authorization: .whileInUse), service = Service()
        service.fails = true
        let coordinator = InAppNavigationCoordinator(location: location, service: service)
        coordinator.start(destination) {}
        location.emitFix(fix())
        await wait { coordinator.phase == .routeFailed }
        service.fails = false
        coordinator.start(destination) {}
        location.emitFix(fix())
        await wait { coordinator.phase == .guiding }
        coordinator.stop()
    }
    func testFutureAndInaccurateFixesAreRejected() {
        let now = Date()
        XCTAssertFalse(InAppNavigationCoordinator.usable(fix(age: -60), now: now))
        XCTAssertFalse(InAppNavigationCoordinator.usable(fix(accuracy: nil), now: now))
        XCTAssertFalse(InAppNavigationCoordinator.usable(fix(accuracy: 101), now: now))
        XCTAssertTrue(InAppNavigationCoordinator.usable(fix(accuracy: 5), now: now))
    }
}
