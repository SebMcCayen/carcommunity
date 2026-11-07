import Foundation
import UIKit
#if DEBUG
import FirebaseCore
#endif

@MainActor
enum InAppNavigationFactory {
    static func service() -> any InAppNavigationService {
        #if DEBUG
        if ConfiglessPlaceSearchFixture.isEnabled { return ConfiglessNavigationService() }
        #endif
        return MapboxInAppNavigationService()
    }
    static func location(default provider: any LocationProvider) -> any LocationProvider {
        #if DEBUG
        if ConfiglessPlaceSearchFixture.isEnabled { return ConfiglessNavigationLocation() }
        #endif
        return provider
    }
}

#if DEBUG
/// Only credential-free Debug builds can reach these no-network test doubles.
@MainActor
private struct ConfiglessNavigationService: InAppNavigationService {
    var isAvailable: Bool { true }
    func route(origin: LocationFix, destination: PlaceSuggestion,
               onFinish: @escaping @MainActor () -> Void) async throws -> any InAppGuidanceSession {
        if ProcessInfo.processInfo.arguments.contains("-KCCUITestNavigationFailure") { throw URLError(.notConnectedToInternet) }
        return ConfiglessGuidanceSession(destination: destination, onFinish: onFinish)
    }
}

@MainActor
private final class ConfiglessGuidanceSession: InAppGuidanceSession {
    let viewController: UIViewController
    init(destination: PlaceSuggestion, onFinish: @escaping @MainActor () -> Void) {
        let controller = UIViewController()
        let name = UILabel()
        name.text = "In-app guidance: \(destination.name)"
        name.numberOfLines = 0
        name.accessibilityIdentifier = "navigation.fixture.destination"
        let end = UIButton(type: .system)
        end.setTitle("Stop navigation", for: .normal)
        end.accessibilityIdentifier = "navigation.fixture.stop"
        end.addAction(UIAction { _ in onFinish() }, for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [name, end])
        stack.axis = .vertical; stack.spacing = 24
        stack.translatesAutoresizingMaskIntoConstraints = false
        controller.view.backgroundColor = .systemBackground
        controller.view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            stack.centerYAnchor.constraint(equalTo: controller.view.centerYAnchor),
            end.heightAnchor.constraint(greaterThanOrEqualToConstant: 48)
        ])
        viewController = controller
    }
    func stop() {}
}

@MainActor
private final class ConfiglessNavigationLocation: LocationProvider {
    let authorization = LocationAuthorization.whileInUse
    func authorizationUpdates() -> AsyncStream<LocationAuthorization> { AsyncStream { $0.yield(.whileInUse) } }
    func requestWhenInUseAuthorization() {}
    func fixes() -> AsyncStream<LocationFix> {
        AsyncStream { continuation in
            if let fix = LocationFix.of(latitude: 57.489, longitude: 12.076, timestamp: Date(), accuracyMeters: 5) {
                continuation.yield(fix)
            }
        }
    }
}
#endif
