import CoreLocation
import Foundation
import UIKit

#if KCC_NAVIGATION_SDK
@preconcurrency import MapboxNavigationCore
@preconcurrency import MapboxNavigationUIKit

/// Only constructed after Navigate, never at launch/search/preview. Free Drive
/// is intentionally never started. SDK active guidance owns rerouting, voice,
/// instructions, progress, ETA and following camera inside CarCommunity.
@MainActor
struct MapboxInAppNavigationService: InAppNavigationService {
    var isAvailable: Bool { MapboxConfiguration.accessToken() != nil }

    func route(origin: LocationFix, destination: PlaceSuggestion,
               onFinish: @escaping @MainActor () -> Void) async throws -> any InAppGuidanceSession {
        guard let token = MapboxConfiguration.accessToken(), InAppNavigationCoordinator.usable(origin),
              SavedPlacesPolicy.isValid(point: destination.point) else { throw InAppRoutingError.unavailable }
        let provider = MapboxNavigationProvider(coreConfig: CoreConfig(
            credentials: NavigationCoreApiConfiguration(accessToken: token),
            locale: Locale.current,
            disableBackgroundTrackingLocation: false,
            predictiveCacheConfig: nil
        ))
        let options = NavigationRouteOptions(coordinates: [
            CLLocationCoordinate2D(latitude: origin.latitude, longitude: origin.longitude),
            CLLocationCoordinate2D(latitude: destination.point.latitude, longitude: destination.point.longitude)
        ])
        options.locale = Locale.current
        options.distanceUnit = .kilometer
        let request = provider.mapboxNavigation.routingProvider().calculateRoutes(options: options)
        let routes = try await withTaskCancellationHandler {
            try await request.value
        } onCancel: {
            request.cancel()
        }
        try Task.checkCancellation()
        let controller = NavigationViewController(navigationRoutes: routes, navigationOptions: NavigationOptions(
            mapboxNavigation: provider.mapboxNavigation,
            voiceController: provider.routeVoiceController,
            eventsManager: provider.eventsManager()
        ))
        controller.routeLineTracksTraversal = true
        return MapboxGuidanceSession(provider: provider, controller: controller, onFinish: onFinish)
    }
}

@MainActor
private final class MapboxGuidanceSession: NSObject, InAppGuidanceSession, @preconcurrency NavigationViewControllerDelegate {
    private var provider: MapboxNavigationProvider?
    private let controller: NavigationViewController
    private let onFinish: @MainActor () -> Void
    private var stopped = false
    var viewController: UIViewController { controller }

    init(provider: MapboxNavigationProvider, controller: NavigationViewController,
         onFinish: @escaping @MainActor () -> Void) {
        self.provider = provider; self.controller = controller; self.onFinish = onFinish
        super.init()
        controller.delegate = self
    }
    func stop() {
        guard !stopped else { return }; stopped = true
        controller.delegate = nil
        provider?.mapboxNavigation.tripSession().setToIdle()
        provider = nil
    }
    func navigationViewControllerDidDismiss(_ navigationViewController: NavigationViewController, byCanceling canceled: Bool) {
        stop(); onFinish()
    }
}
#else
/// Secret-free developer/CI variant, equivalent to Android's noNav build.
/// The production navigation project links and separately validates the SDK.
@MainActor
struct MapboxInAppNavigationService: InAppNavigationService {
    var isAvailable: Bool { false }
    func route(origin: LocationFix, destination: PlaceSuggestion,
               onFinish: @escaping @MainActor () -> Void) async throws -> any InAppGuidanceSession {
        throw InAppRoutingError.unavailable
    }
}
#endif

private enum InAppRoutingError: Error { case unavailable }
