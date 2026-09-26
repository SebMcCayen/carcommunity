import SwiftUI
import MapboxMaps
import CoreLocation
import UIKit

/// Mirrors the convoy-camera ownership rules Android already applies in
/// `MapboxMapSurface.setConvoyFit/applyConvoyFit`: turning focus OFF restores
/// normal Me framing, a temporary lack of fittable points while focus stays ON
/// leaves the current camera alone, and the fit itself is computed on a flat
/// camera before re-applying the live pitch.
enum MapHomeConvoyViewportPolicy {
    enum Plan: Equatable {
        case restoreBrowsing
        case keepCurrentViewport
        case fitConvoy
    }

    static let fitComputationPitch: CGFloat = 0
    static let minimumFitZoom: CGFloat = 8
    static let maximumFitZoom: CGFloat = 16

    static func plan(points: [MapPoint]?, focusEnabled: Bool) -> Plan {
        guard focusEnabled else { return .restoreBrowsing }
        guard let points, points.count >= 2 else { return .keepCurrentViewport }
        return .fitConvoy
    }

    static func fitZoom(_ rawZoom: CGFloat?, fallback: CGFloat) -> CGFloat {
        let zoom = rawZoom.map { $0.isFinite ? $0 : fallback } ?? fallback
        return min(max(zoom, minimumFitZoom), maximumFitZoom)
    }
}

/// The complete camera destination currently owned by the renderer.
///
/// Mapbox reports the camera's in-flight value while an animation is running.
/// Starting a second full-camera animation from that value can therefore
/// resurrect an old pitch or zoom. Keeping the latest destination lets every
/// subsequent command merge into the destination that is actually being
/// approached instead of an intermediate frame.
struct MapHomeCameraTarget: Equatable {
    let latitude: Double
    let longitude: Double
    let zoom: Double
    let bearing: Double
    let pitch: Double

    func applyingPreferences(
        is3D: Bool,
        browsingZoom: Double,
        browsingOwnsZoom: Bool
    ) -> MapHomeCameraTarget {
        MapHomeCameraTarget(
            latitude: latitude,
            longitude: longitude,
            zoom: browsingOwnsZoom ? browsingZoom : zoom,
            bearing: bearing,
            pitch: is3D ? 45 : 0
        )
    }
}

enum MapHomeMeFollowPolicy {
    static let idleReturnDelay: Duration = .seconds(10)

    struct SubscriptionKey: Equatable {
        let surfaceActive: Bool
        let enabled: Bool
        let authorization: LocationAuthorization
        let providerId: ObjectIdentifier
    }

    struct RestoreState: Equatable {
        let latestOwnPoint: MapPoint?
        let suspended: Bool
    }

    struct Camera: Equatable {
        let center: CLLocationCoordinate2D
        let zoom: CGFloat
        let bearing: CGFloat
        let pitch: CGFloat
    }

    struct FixUpdate: Equatable {
        let latestOwnPoint: MapPoint
        let shouldApplyCamera: Bool
    }

    static func subscriptionKey(
        surfaceActive: Bool,
        enabled: Bool,
        authorization: LocationAuthorization,
        locationProvider: any LocationProvider
    ) -> SubscriptionKey {
        SubscriptionKey(
            surfaceActive: surfaceActive,
            enabled: enabled,
            authorization: authorization,
            providerId: ObjectIdentifier(locationProvider as AnyObject)
        )
    }

    static func restoreState(
        latestOwnPoint: MapPoint?,
        userPoint: MapPoint?,
        resumeSelfFollow: Bool,
        suspended: Bool
    ) -> RestoreState {
        RestoreState(
            // Resuming after convoy focus must not reuse a fix cached before
            // the convoy owned the camera. Prefer the caller's fresh RTDB
            // point; if it is unavailable, keep the browsing camera until
            // the shared provider supplies its next device fix.
            latestOwnPoint: userPoint ?? (resumeSelfFollow ? nil : latestOwnPoint),
            suspended: resumeSelfFollow ? false : suspended
        )
    }

    static func ingestFix(
        _ fix: LocationFix,
        followEnabled: Bool,
        suspended: Bool
    ) -> FixUpdate {
        let point = MapPoint(longitude: fix.longitude, latitude: fix.latitude)
        return FixUpdate(
            latestOwnPoint: point,
            shouldApplyCamera: followEnabled && !suspended
        )
    }

    static func shouldConsumeFixes(
        surfaceActive: Bool,
        followEnabled: Bool,
        authorization: LocationAuthorization
    ) -> Bool {
        surfaceActive && followEnabled && authorization.isAuthorized
    }

    static func shouldRestoreBrowsingOnAuthorizationChange(
        previous: LocationAuthorization,
        current: LocationAuthorization,
        followEnabled: Bool
    ) -> Bool {
        followEnabled && previous.isAuthorized && !current.isAuthorized
    }

    @MainActor
    static func camera(
        point: MapPoint?,
        snapshot: MapCameraSnapshot?,
        fallback: MapCameraSnapshot?,
        restoringBrowsing: Bool,
        browsingZoom: Double = StubMapSurface.defaultBrowsingZoom
    ) -> Camera? {
        let preferred = restoringBrowsing ? (fallback ?? snapshot) : (snapshot ?? fallback)
        let center = point.map {
            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
        } ?? preferred.map {
            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
        }
        guard let center else { return nil }
        // A convoy/route camera may have owned zoom while the user changed the
        // resting browsing preference. Once browsing ownership resumes, use
        // that latest preference even if no fresh own-location point exists;
        // the saved pre-owner camera still supplies center, bearing and pitch.
        let zoom = restoringBrowsing
            ? CGFloat(browsingZoom)
            : CGFloat(preferred?.zoom ?? Double(StubMapSurface.defaultBrowsingZoom))
        return Camera(
            center: center,
            zoom: zoom,
            bearing: CGFloat(preferred?.bearing ?? 0),
            pitch: CGFloat(preferred?.pitch ?? 45)
        )
    }

    @MainActor
    static func authorizationStream(
        for locationProvider: any LocationProvider
    ) -> AsyncStream<LocationAuthorization> {
        // LocationProvider guarantees that this stream atomically seeds its
        // current value before delivering changes. Adding a second seed here
        // can race that subscription and replay a stale authorization after a
        // newer value has already been set.
        locationProvider.authorizationUpdates()
    }

}

enum MapHomeProjectionTrustPolicy {
    private static let maximumMercatorLatitude = 85.05112878
    static let roundTripTolerancePixels = 4.0
    static let minimumRoundTripToleranceMeters = 2.0
    static let maximumFlatPitchDegrees = 1.0

    static func hasFiniteScreenPosition(x: Double, y: Double) -> Bool {
        x.isFinite && y.isFinite
    }

    static func requiresRoundTrip(pitch: Double) -> Bool {
        !pitch.isFinite || pitch > maximumFlatPitchDegrees
    }

    static func isTrustworthy(
        latitude: Double,
        longitude: Double,
        unprojectedLatitude: Double,
        unprojectedLongitude: Double,
        zoom: Double
    ) -> Bool {
        guard latitude.isFinite, longitude.isFinite,
              unprojectedLatitude.isFinite, unprojectedLongitude.isFinite else {
            return false
        }
        let tolerance = max(
            metersPerPixel(latitude: latitude, zoom: zoom) * roundTripTolerancePixels,
            minimumRoundTripToleranceMeters
        )
        let mismatch = LiveShareCadence.distanceMeters(
            lat1: latitude, lon1: longitude,
            lat2: unprojectedLatitude, lon2: unprojectedLongitude
        )
        return mismatch <= tolerance
    }

    static func metersPerPixel(latitude: Double, zoom: Double) -> Double {
        guard latitude.isFinite, zoom.isFinite else { return 0 }
        let clampedLatitude = min(max(latitude, -maximumMercatorLatitude), maximumMercatorLatitude)
        let clampedZoom = max(zoom, 0)
        return 156_543.03392 * cos(clampedLatitude * .pi / 180) / pow(2, clampedZoom)
    }
}

private enum MapHomeStyleLayers {
    static let standardImportId = "basemap"
    static let lightPresetConfig = "lightPreset"
    static let show3DObjectsConfig = "show3dObjects"
    static let trafficSourceId = "kcc-traffic-source"
    static let trafficLayerId = "kcc-traffic-layer"

    static func applyMapMode(_ mode: MapMode, to map: MapboxMap) {
        try? map.setStyleImportConfigProperty(
            for: standardImportId,
            config: lightPresetConfig,
            value: mode == .night ? "night" : "day"
        )
    }

    static func apply3D(_ enabled: Bool, to map: MapboxMap) {
        try? map.setStyleImportConfigProperty(
            for: standardImportId,
            config: show3DObjectsConfig,
            value: enabled
        )
    }

    static func applyTraffic(_ visible: Bool, mode: MapMode, to map: MapboxMap) {
        if !map.sourceExists(withId: trafficSourceId) {
            var source = VectorSource(id: trafficSourceId)
            source.url = "mapbox://mapbox.mapbox-traffic-v1"
            try? map.addSource(source)
        }
        if !map.layerExists(withId: trafficLayerId) {
            var layer = LineLayer(id: trafficLayerId, source: trafficSourceId)
            layer.sourceLayer = "traffic"
            layer.slot = "middle"
            layer.lineCap = .constant(.round)
            layer.lineJoin = .constant(.round)
            layer.visibility = .constant(visible ? .visible : .none)
            layer.lineColor = .expression(colorExpression(for: mode))
            layer.lineWidth = .constant(MapTrafficPalette.lineWidth(for: mode))
            try? map.addLayer(layer)
            return
        }
        try? map.updateLayer(withId: trafficLayerId, type: LineLayer.self) { layer in
            layer.visibility = .constant(visible ? .visible : .none)
            layer.lineColor = .expression(colorExpression(for: mode))
            layer.lineWidth = .constant(MapTrafficPalette.lineWidth(for: mode))
        }
    }

    private static func colorExpression(for mode: MapMode) -> Exp {
        let colors = MapTrafficPalette.colors(for: mode)
        return Exp(.match) {
            Exp(.get) { "congestion" }
            "low"
            color(colors.low)
            "moderate"
            color(colors.moderate)
            "heavy"
            color(colors.heavy)
            "severe"
            color(colors.severe)
            color(colors.unknown)
        }
    }

    private static func color(_ argb: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((argb >> 16) & 0xFF) / 255,
            green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255,
            alpha: CGFloat((argb >> 24) & 0xFF) / 255
        )
    }
}

@MainActor
private final class ConvoyViewportInteractionObserver: @preconcurrency ViewportStatusObserver {
    weak var surface: StubMapSurface?
    var onUserInteraction: @MainActor () -> Void = {}

    init(surface: StubMapSurface) {
        self.surface = surface
    }

    func viewportStatusDidChange(
        from fromStatus: ViewportStatus,
        to toStatus: ViewportStatus,
        reason: ViewportStatusChangeReason
    ) {
        guard reason == .userInteraction else { return }
        surface?.suspendConvoyFitForInteraction()
        onUserInteraction()
    }
}

/// Hosts the shell's single Mapbox Standard map. Config-less builds keep the
/// deterministic placeholder, while a valid public token swaps in the native
/// renderer without changing the shell-facing ``MapSurface`` seam.
@MainActor
struct MapHomeView: View {
    /// The shell's one surface instance, owned by ``ShellView`` — composed
    /// once for the whole signed-in shell and never disposed.
    let surface: StubMapSurface
    let locationProvider: any LocationProvider
    private let accessToken: String?

    init(
        surface: StubMapSurface,
        locationProvider: any LocationProvider,
        accessToken: String? = MapboxConfiguration.accessToken()
    ) {
        self.surface = surface
        self.locationProvider = locationProvider
        self.accessToken = accessToken
    }

    var body: some View {
        ZStack {
            if let accessToken {
                MapboxStandardMap(
                    accessToken: accessToken,
                    surface: surface,
                    locationProvider: locationProvider
                ) {
                    surface.markLoaded()
                }
            } else {
                mapPlaceholder
            }
        }
        // Load-state chip, mirroring Android's `LoadingRoadsChip`: visible
        // while style/tiles load, gone once the map is interactive.
        .overlay(alignment: .top) {
            if surface.loadState == .loading {
                loadingRoadsChip
                    .padding(.top, KccSpacing.s4)
            }
        }
        // Only the config-less placeholder simulates loading. The real map
        // clears the chip from Mapbox's onMapLoaded event.
        .task {
            guard accessToken == nil else { return }
            await surface.simulateInitialLoadIfNeeded()
        }
    }

    private var mapPlaceholder: some View {
        ZStack {
            Color(.secondarySystemBackground)
                .ignoresSafeArea()
            Text("shell.mapPlaceholder")
                .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.medium))
                .foregroundStyle(.secondary)
        }
    }

    private var loadingRoadsChip: some View {
        HStack(spacing: KccSpacing.s2) {
            ProgressView()
                .controlSize(.small)
            Text("shell.loadingRoads")
                .font(.system(size: KccTypeScale.bodySm))
        }
        .padding(.horizontal, KccSpacing.s3)
        .padding(.vertical, KccSpacing.s1 + 2)
        .background(.regularMaterial, in: Capsule())
    }
}

/// Mapbox-specific rendering stays confined to this file. The initial camera
/// opens over Kungsbacka and the Standard style matches Android's default.
@MainActor
private struct MapboxStandardMap: View {
    let onLoaded: @MainActor () -> Void
    let surface: StubMapSurface
    let locationProvider: any LocationProvider
    @State private var viewport: Viewport
    @State private var cameraBeforeConvoy: MapCameraSnapshot?
    @State private var interactionObserver: ConvoyViewportInteractionObserver
    @State private var latestOwnPoint: MapPoint?
    @State private var meFollowEnabled = false
    @State private var meFollowSuspended = false
    @State private var locationAuthorization: LocationAuthorization
    @State private var surfaceActive: Bool
    @State private var pendingProgrammaticViewportTokens: Set<UUID> = []
    @State private var canonicalCameraTarget: MapHomeCameraTarget?
    @State private var meFollowIdleTask: Task<Void, Never>?
    @State private var meFollowIdleTaskToken: UUID?
    @State private var rendererAttached = false
    @State private var isVisible = false

    init(
        accessToken: String,
        surface: StubMapSurface,
        locationProvider: any LocationProvider,
        onLoaded: @escaping @MainActor () -> Void
    ) {
        MapboxOptions.accessToken = accessToken
        self.surface = surface
        self.locationProvider = locationProvider
        self.onLoaded = onLoaded
        _viewport = State(initialValue: .camera(
            center: .init(latitude: 57.4872, longitude: 12.0761),
            zoom: surface.browsingZoom,
            bearing: 0,
            pitch: surface.is3D ? 45 : 0
        ))
        _interactionObserver = State(initialValue: ConvoyViewportInteractionObserver(surface: surface))
        _locationAuthorization = State(initialValue: locationProvider.authorization)
        _surfaceActive = State(initialValue: surface.isActive)
    }

    var body: some View {
        MapReader { proxy in
            MapboxMaps.Map(viewport: $viewport) {
                if let trail = surface.followMeTrail, trail.count >= 2 {
                    PolylineAnnotation(
                        id: "convoy-follow-me-trail",
                        lineCoordinates: trail.map {
                            CLLocationCoordinate2D(
                                latitude: $0.latitude,
                                longitude: $0.longitude
                            )
                        }
                    )
                    .lineColor(UIColor.systemYellow)
                    .lineBorderColor(UIColor.black.withAlphaComponent(0.65))
                    .lineBorderWidth(2)
                    .lineWidth(6)
                    .lineOpacity(0.92)
                    .lineJoin(.round)
                }
            }
            .mapStyle(.standard)
            .onMapLoaded { _ in
                onLoaded()
                if !rendererAttached, let viewportController = proxy.viewport {
                    viewportController.addStatusObserver(interactionObserver)
                    rendererAttached = true
                }
                installRenderer(
                    proxy.map,
                    viewport: $viewport,
                    cameraBeforeConvoy: $cameraBeforeConvoy,
                    latestOwnPoint: $latestOwnPoint,
                    meFollowEnabled: $meFollowEnabled,
                    meFollowSuspended: $meFollowSuspended
                )
            }
            .onCameraChanged { context in
                let state = context.cameraState
                if pendingProgrammaticViewportTokens.isEmpty {
                    canonicalCameraTarget = cameraTarget(from: state)
                }
                surface.updateCameraSnapshot(.of(
                    latitude: state.center.latitude,
                    longitude: state.center.longitude,
                    zoom: state.zoom,
                    bearing: state.bearing,
                    pitch: state.pitch
                ))
                if meFollowEnabled, meFollowSuspended,
                   pendingProgrammaticViewportTokens.isEmpty {
                    armMeFollowIdleReturn(viewport: $viewport)
                }
            }
            .onDisappear {
                isVisible = false
                meFollowIdleTask?.cancel()
                meFollowIdleTask = nil
                meFollowIdleTaskToken = nil
                if rendererAttached {
                    proxy.viewport?.removeStatusObserver(interactionObserver)
                    rendererAttached = false
                }
                surface.removeRenderer()
            }
            .onAppear {
                isVisible = true
                interactionObserver.onUserInteraction = {
                    // A gesture can interrupt an in-flight programmatic animation.
                    // Always suspend now; idle return waits for its token to clear.
                    canonicalCameraTarget = nil
                    guard meFollowEnabled else { return }
                    meFollowSuspended = true
                    surface.suspendSelfFollowForInteraction()
                    armMeFollowIdleReturn(viewport: $viewport)
                }
                // onMapLoaded handles first readiness. A retained Mapbox view
                // does not necessarily emit it again after disappearing, so
                // reattach immediately when its map is already available.
                if let map = proxy.map {
                    if !rendererAttached, let viewportController = proxy.viewport {
                        viewportController.addStatusObserver(interactionObserver)
                        rendererAttached = true
                    }
                    installRenderer(
                        map,
                        viewport: $viewport,
                        cameraBeforeConvoy: $cameraBeforeConvoy,
                        latestOwnPoint: $latestOwnPoint,
                        meFollowEnabled: $meFollowEnabled,
                        meFollowSuspended: $meFollowSuspended
                    )
                }
                armMeFollowIdleReturn(viewport: $viewport)
            }
            .onChange(of: surface.isActive, initial: true) { _, active in
                surfaceActive = active
            }
            .ignoresSafeArea()
        }
        .task(id: MapHomeMeFollowPolicy.subscriptionKey(
            surfaceActive: surfaceActive,
            enabled: meFollowEnabled,
            authorization: locationAuthorization,
            locationProvider: locationProvider
        )) {
            guard MapHomeMeFollowPolicy.shouldConsumeFixes(
                surfaceActive: surfaceActive,
                followEnabled: meFollowEnabled,
                authorization: locationAuthorization
            ) else { return }
            for await fix in locationProvider.fixes() {
                if Task.isCancelled { return }
                let update = MapHomeMeFollowPolicy.ingestFix(
                    fix,
                    followEnabled: meFollowEnabled,
                    suspended: meFollowSuspended
                )
                latestOwnPoint = update.latestOwnPoint
                guard update.shouldApplyCamera else { continue }
                applyMeFollow(update.latestOwnPoint, viewport: $viewport)
            }
        }
        .task(id: ObjectIdentifier(locationProvider as AnyObject)) {
            for await authorization in MapHomeMeFollowPolicy.authorizationStream(
                for: locationProvider
            ) {
                if Task.isCancelled { return }
                locationAuthorization = authorization
            }
        }
        .onChange(of: locationAuthorization) { oldValue, newValue in
            guard MapHomeMeFollowPolicy.shouldRestoreBrowsingOnAuthorizationChange(
                previous: oldValue,
                current: newValue,
                followEnabled: meFollowEnabled
            ) else { return }
            meFollowEnabled = false
            meFollowSuspended = false
            meFollowIdleTask?.cancel()
            meFollowIdleTask = nil
            meFollowIdleTaskToken = nil
            latestOwnPoint = nil
            let fallback = cameraBeforeConvoy
            cameraBeforeConvoy = nil
            surface.setConvoyFit(
                points: nil,
                focusEnabled: false,
                userPoint: nil,
                followSelfEnabled: false
            )
            applyMeFollow(
                nil,
                viewport: $viewport,
                fallback: fallback,
                snapshot: surface.cameraSnapshot,
                restoringBrowsing: true
            )
        }
    }

    private func installRenderer(
        _ map: MapboxMap?,
        viewport: Binding<Viewport>,
        cameraBeforeConvoy: Binding<MapCameraSnapshot?>,
        latestOwnPoint: Binding<MapPoint?>,
        meFollowEnabled: Binding<Bool>,
        meFollowSuspended: Binding<Bool>
    ) {
        guard let map else { return }
        surface.installRenderer(
            projection: { latitude, longitude in
                let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
                let point = map.point(for: coordinate)
                guard MapHomeProjectionTrustPolicy.hasFiniteScreenPosition(
                    x: point.x,
                    y: point.y
                ) else {
                    return nil
                }
                let cameraState = map.cameraState
                let trustworthy: Bool
                if MapHomeProjectionTrustPolicy.requiresRoundTrip(pitch: cameraState.pitch) {
                    let roundTrip = map.coordinate(for: point)
                    trustworthy = MapHomeProjectionTrustPolicy.isTrustworthy(
                        latitude: latitude,
                        longitude: longitude,
                        unprojectedLatitude: roundTrip.latitude,
                        unprojectedLongitude: roundTrip.longitude,
                        zoom: cameraState.zoom
                    )
                } else {
                    // A top-down camera cannot fold a point behind itself. Avoid
                    // a native unprojection for every marker in the common 2D
                    // browsing state, matching the Android surface.
                    trustworthy = true
                }
                return MapScreenPoint(x: point.x, y: point.y, trustworthy: trustworthy)
            },
            convoyFit: {
                points,
                enabled,
                userPoint,
                followSelfEnabled,
                resumeSelfFollow,
                restoreViewport in
                let state = map.cameraState
                let baseTarget = canonicalCameraTarget ?? cameraTarget(from: state)
                meFollowEnabled.wrappedValue = !enabled && followSelfEnabled
                if enabled || !followSelfEnabled {
                    meFollowIdleTask?.cancel()
                    meFollowIdleTask = nil
                    meFollowIdleTaskToken = nil
                }
                switch MapHomeConvoyViewportPolicy.plan(points: points, focusEnabled: enabled) {
                case .keepCurrentViewport:
                    return
                case .restoreBrowsing:
                    guard restoreViewport else { return }
                    let fallback = cameraBeforeConvoy.wrappedValue
                    cameraBeforeConvoy.wrappedValue = nil
                    let restoreState = MapHomeMeFollowPolicy.restoreState(
                        latestOwnPoint: latestOwnPoint.wrappedValue,
                        userPoint: userPoint,
                        resumeSelfFollow: resumeSelfFollow,
                        suspended: meFollowSuspended.wrappedValue
                    )
                    latestOwnPoint.wrappedValue = restoreState.latestOwnPoint
                    meFollowSuspended.wrappedValue = restoreState.suspended
                    applyMeFollow(
                        restoreState.latestOwnPoint,
                        viewport: viewport,
                        fallback: fallback,
                        snapshot: .of(
                            latitude: state.center.latitude,
                            longitude: state.center.longitude,
                            zoom: state.zoom,
                            bearing: state.bearing,
                            pitch: state.pitch
                        ),
                        restoringBrowsing: true
                    )
                    return
                case .fitConvoy:
                    break
                }
                guard let points, points.count >= 2 else { return }
                if cameraBeforeConvoy.wrappedValue == nil {
                    cameraBeforeConvoy.wrappedValue = .of(
                        latitude: baseTarget.latitude,
                        longitude: baseTarget.longitude,
                        zoom: baseTarget.zoom,
                        bearing: baseTarget.bearing,
                        pitch: baseTarget.pitch
                    )
                }
                let coordinates = points.map {
                    CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                }
                guard let camera = try? map.camera(
                    for: coordinates,
                    camera: CameraOptions(
                        bearing: baseTarget.bearing,
                        pitch: MapHomeConvoyViewportPolicy.fitComputationPitch
                    ),
                    coordinatesPadding: UIEdgeInsets(top: 110, left: 60, bottom: 150, right: 60),
                    maxZoom: MapHomeConvoyViewportPolicy.maximumFitZoom,
                    offset: nil
                ) else { return }
                applyCameraTarget(
                    MapHomeCameraTarget(
                        latitude: (camera.center ?? state.center).latitude,
                        longitude: (camera.center ?? state.center).longitude,
                        zoom: Double(MapHomeConvoyViewportPolicy.fitZoom(
                            camera.zoom, fallback: CGFloat(baseTarget.zoom)
                        )),
                        bearing: baseTarget.bearing,
                        pitch: surface.is3D ? 45 : 0
                    ),
                    duration: 0.9,
                    viewport: viewport
                )
            },
            center: { point in
                if meFollowEnabled.wrappedValue {
                    meFollowSuspended.wrappedValue = true
                }
                let state = map.cameraState
                let baseTarget = canonicalCameraTarget ?? cameraTarget(from: state)
                applyCameraTarget(
                    MapHomeCameraTarget(
                        latitude: point.latitude,
                        longitude: point.longitude,
                        zoom: baseTarget.zoom,
                        bearing: baseTarget.bearing,
                        pitch: baseTarget.pitch
                    ),
                    duration: 0.9,
                    viewport: viewport
                )
            },
            traffic: { enabled, mode in
                MapHomeStyleLayers.applyTraffic(enabled, mode: mode, to: map)
            },
            mapMode: { mode in
                MapHomeStyleLayers.applyMapMode(mode, to: map)
            },
            cameraPreferences: { enabled, zoom in
                MapHomeStyleLayers.apply3D(enabled, to: map)
                let state = map.cameraState
                let target = (canonicalCameraTarget ?? cameraTarget(from: state))
                    .applyingPreferences(
                        is3D: enabled,
                        browsingZoom: zoom,
                        browsingOwnsZoom: !surface.convoyFocusEnabled
                            && surface.routeOverlay == nil
                    )
                guard canonicalCameraTarget != target else { return }
                guard abs(state.pitch - target.pitch) > 0.01
                        || abs(state.zoom - target.zoom) > 0.01
                else { return }
                applyCameraTarget(target, duration: 0.5, viewport: viewport)
            }
        )
    }

    private func cameraTarget(from state: CameraState) -> MapHomeCameraTarget {
        MapHomeCameraTarget(
            latitude: state.center.latitude,
            longitude: state.center.longitude,
            zoom: state.zoom,
            bearing: state.bearing,
            pitch: state.pitch
        )
    }

    private func applyCameraTarget(
        _ target: MapHomeCameraTarget,
        duration: TimeInterval,
        viewport: Binding<Viewport>
    ) {
        canonicalCameraTarget = target
        performProgrammaticViewportChange {
            withViewportAnimation(.easeInOut(duration: duration)) {
                viewport.wrappedValue = .camera(
                    center: CLLocationCoordinate2D(
                        latitude: target.latitude,
                        longitude: target.longitude
                    ),
                    zoom: target.zoom,
                    bearing: target.bearing,
                    pitch: target.pitch
                )
            }
        }
    }

    private func performProgrammaticViewportChange(_ change: () -> Void) {
        let token = UUID()
        pendingProgrammaticViewportTokens.insert(token)
        Task { @MainActor in
            do {
                try await Task.sleep(for: .seconds(2))
            } catch {
                return
            }
            pendingProgrammaticViewportTokens.remove(token)
            if pendingProgrammaticViewportTokens.isEmpty {
                canonicalCameraTarget = nil
            }
            if pendingProgrammaticViewportTokens.isEmpty,
               isVisible,
               meFollowEnabled,
               meFollowSuspended {
                armMeFollowIdleReturn(viewport: $viewport)
            }
        }
        change()
    }

    private func armMeFollowIdleReturn(viewport: Binding<Viewport>) {
        meFollowIdleTask?.cancel()
        guard isVisible,
              surfaceActive,
              meFollowEnabled,
              meFollowSuspended,
              !surface.convoyFocusEnabled,
              pendingProgrammaticViewportTokens.isEmpty
        else {
            meFollowIdleTask = nil
            meFollowIdleTaskToken = nil
            return
        }
        let token = UUID()
        meFollowIdleTaskToken = token
        meFollowIdleTask = Task { @MainActor in
            do {
                try await Task.sleep(for: MapHomeMeFollowPolicy.idleReturnDelay)
            } catch {
                if meFollowIdleTaskToken == token {
                    meFollowIdleTask = nil
                    meFollowIdleTaskToken = nil
                }
                return
            }
            guard !Task.isCancelled,
                  isVisible,
                  surfaceActive,
                  meFollowEnabled,
                  meFollowSuspended,
                  !surface.convoyFocusEnabled,
                  pendingProgrammaticViewportTokens.isEmpty
            else {
                if meFollowIdleTaskToken == token {
                    meFollowIdleTask = nil
                    meFollowIdleTaskToken = nil
                }
                return
            }
            meFollowSuspended = false
            surface.resumeSelfFollowAfterIdle()
            applyMeFollow(latestOwnPoint, viewport: viewport)
            if meFollowIdleTaskToken == token {
                meFollowIdleTask = nil
                meFollowIdleTaskToken = nil
            }
        }
    }

    private func applyMeFollow(
        _ point: MapPoint?,
        viewport: Binding<Viewport>,
        fallback: MapCameraSnapshot? = nil,
        snapshot: MapCameraSnapshot? = nil,
        restoringBrowsing: Bool = false
    ) {
        guard let camera = meFollowCamera(
            point,
            fallback: fallback,
            snapshot: snapshot ?? surface.cameraSnapshot,
            restoringBrowsing: restoringBrowsing
        ) else { return }
        applyCameraTarget(
            MapHomeCameraTarget(
                latitude: camera.center.latitude,
                longitude: camera.center.longitude,
                zoom: Double(camera.zoom),
                bearing: Double(camera.bearing),
                pitch: surface.is3D ? 45 : 0
            ),
            duration: 0.9,
            viewport: viewport
        )
    }

    private func meFollowCamera(
        _ point: MapPoint?,
        fallback: MapCameraSnapshot?,
        snapshot: MapCameraSnapshot?,
        restoringBrowsing: Bool
    ) -> (center: CLLocationCoordinate2D, zoom: CGFloat, bearing: CGFloat, pitch: CGFloat)? {
        MapHomeMeFollowPolicy.camera(
            point: point,
            snapshot: snapshot,
            fallback: fallback,
            restoringBrowsing: restoringBrowsing,
            browsingZoom: surface.browsingZoom
        ).map { camera in
            (
                center: camera.center,
                zoom: camera.zoom,
                bearing: camera.bearing,
                pitch: camera.pitch
            )
        }
    }
}

#Preview("Loading") {
    MapHomeView(
        surface: StubMapSurface(initialState: .loading, autoLoad: false),
        locationProvider: StubLocationProvider()
    )
}

#Preview("Loaded") {
    MapHomeView(
        surface: StubMapSurface(initialState: .loaded, autoLoad: false),
        locationProvider: StubLocationProvider()
    )
}
