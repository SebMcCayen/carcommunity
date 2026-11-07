import Foundation
import Observation
import UIKit

@MainActor
protocol InAppGuidanceSession: AnyObject {
    var viewController: UIViewController { get }
    func stop()
}

@MainActor
protocol InAppNavigationService {
    var isAvailable: Bool { get }
    func route(origin: LocationFix, destination: PlaceSuggestion, onFinish: @escaping @MainActor () -> Void) async throws -> any InAppGuidanceSession
}

enum InAppNavigationPhase: Equatable {
    case idle, locating, routing, guiding, unavailable, locationDenied, locationUnavailable, routeFailed
}

/// Owns one explicitly requested trip. No provider, routing request or trip is
/// created merely by opening the app, searching, or selecting a result.
@MainActor @Observable
final class InAppNavigationCoordinator {
    private let location: any LocationProvider
    private let service: any InAppNavigationService
    private let locationTimeout: Duration
    private var generation = 0
    private(set) var phase: InAppNavigationPhase = .idle
    private(set) var session: (any InAppGuidanceSession)?
    @ObservationIgnored nonisolated(unsafe) private var operation: Task<Void, Never>?

    init(location: any LocationProvider, service: any InAppNavigationService,
         locationTimeout: Duration = .seconds(30)) {
        self.location = location; self.service = service; self.locationTimeout = locationTimeout
    }
    deinit { operation?.cancel() }

    nonisolated static func usable(_ fix: LocationFix, now: Date = Date()) -> Bool {
        let age = now.timeIntervalSince(fix.timestamp)
        return fix.latitude.isFinite && fix.longitude.isFinite && (-90...90).contains(fix.latitude)
            && (-180...180).contains(fix.longitude) && (-5...30).contains(age)
            && fix.accuracyMeters.map { $0.isFinite && (0...100).contains($0) } == true
    }
    func start(_ destination: PlaceSuggestion, onFinish: @escaping @MainActor () -> Void) {
        stop()
        guard service.isAvailable, SavedPlacesPolicy.isValid(point: destination.point) else { phase = .unavailable; return }
        guard location.authorization != .denied else { phase = .locationDenied; return }
        let epoch = generation
        phase = .locating
        // This path follows the user's explicit Navigate action. The existing
        // first-launch permission prompt usually already supplied the grant.
        if location.authorization == .notDetermined { location.requestWhenInUseAuthorization() }
        let fixes = location.fixes(), authorization = location.authorizationUpdates()
        operation = Task { [weak self, service, locationTimeout] in
            do {
                let origin = try await withThrowingTaskGroup(of: LocationFix.self) { group in
                    group.addTask {
                        for await fix in fixes {
                            try Task.checkCancellation()
                            if Self.usable(fix) { return fix }
                        }
                        throw CancellationError()
                    }
                    group.addTask {
                        for await status in authorization {
                            try Task.checkCancellation()
                            if status == .denied { throw NavigationPreparationError.denied }
                        }
                        throw CancellationError()
                    }
                    group.addTask {
                        try await Task.sleep(for: locationTimeout)
                        throw NavigationPreparationError.timeout
                    }
                    defer { group.cancelAll() }
                    guard let fix = try await group.next() else { throw CancellationError() }
                    return fix
                }
                try Task.checkCancellation()
                guard let self, self.generation == epoch else { return }
                self.phase = .routing
                let session = try await service.route(origin: origin, destination: destination, onFinish: onFinish)
                guard !Task.isCancelled, self.generation == epoch else { session.stop(); return }
                self.session = session; self.phase = .guiding
            } catch {
                guard !Task.isCancelled, let self, self.generation == epoch else { return }
                switch error {
                case NavigationPreparationError.denied: self.phase = .locationDenied
                case NavigationPreparationError.timeout: self.phase = .locationUnavailable
                default: self.phase = self.phase == .routing ? .routeFailed : .locationUnavailable
                }
            }
        }
    }
    func stop() {
        generation += 1
        operation?.cancel(); operation = nil
        session?.stop(); session = nil
        phase = .idle
    }
}

private enum NavigationPreparationError: Error { case denied, timeout }
