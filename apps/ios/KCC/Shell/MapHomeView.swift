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
        restoringBrowsing: Bool
    ) -> Camera? {
        let preferred = restoringBrowsing ? (fallback ?? snapshot) : (snapshot ?? fallback)
        let center = point.map {
            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
        } ?? preferred.map {
            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
        }
        guard let center else { return nil }
        let zoom: CGFloat
        if point != nil, restoringBrowsing {
            zoom = StubMapSurface.defaultBrowsingZoom
        } else {
            zoom = CGFloat(preferred?.zoom ?? Double(StubMapSurface.defaultBrowsingZoom))
        }
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
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            continuation.yield(locationProvider.authorization)
            let task = Task { @MainActor in
                for await authorization in locationProvider.authorizationUpdates() {
                    if Task.isCancelled { break }
                    continuation.yield(authorization)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
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
    @State private var viewport: Viewport = .camera(
        center: .init(latitude: 57.4872, longitude: 12.0761),
        zoom: StubMapSurface.defaultBrowsingZoom,
        bearing: 0,
        pitch: 45
    )
    @State private var cameraBeforeConvoy: MapCameraSnapshot?
    @State private var interactionObserver: ConvoyViewportInteractionObserver
    @State private var latestOwnPoint: MapPoint?
    @State private var meFollowEnabled = false
    @State private var meFollowSuspended = false
    @State private var locationAuthorization: LocationAuthorization
    @State private var surfaceActive: Bool
    @State private var pendingProgrammaticViewportTokens: Set<UUID> = []
    @State private var meFollowIdleTask: Task<Void, Never>?
    @State private var meFollowIdleTaskToken: UUID?

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
        _interactionObserver = State(initialValue: ConvoyViewportInteractionObserver(surface: surface))
        _locationAuthorization = State(initialValue: locationProvider.authorization)
        _surfaceActive = State(initialValue: surface.isActive)
    }

    var body: some View {
        MapReader { proxy in
            MapboxMaps.Map(viewport: $viewport)
            .mapStyle(.standard)
            .onMapLoaded { _ in
                onLoaded()
                proxy.viewport?.addStatusObserver(interactionObserver)
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
                if let token = pendingProgrammaticViewportTokens.first {
                    pendingProgrammaticViewportTokens.remove(token)
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
                meFollowIdleTask?.cancel()
                meFollowIdleTask = nil
                meFollowIdleTaskToken = nil
                proxy.viewport?.removeStatusObserver(interactionObserver)
                surface.removeRenderer()
            }
            .onAppear {
                interactionObserver.onUserInteraction = {
                    guard meFollowEnabled, pendingProgrammaticViewportTokens.isEmpty else { return }
                    meFollowSuspended = true
                    surface.suspendSelfFollowForInteraction()
                    armMeFollowIdleReturn(viewport: $viewport)
                }
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
                guard point.x.isFinite, point.y.isFinite, point.x >= 0, point.y >= 0 else {
                    return nil
                }
                let roundTrip = map.coordinate(for: point)
                let mismatch = LiveShareCadence.distanceMeters(
                    lat1: latitude, lon1: longitude,
                    lat2: roundTrip.latitude, lon2: roundTrip.longitude
                )
                return MapScreenPoint(x: point.x, y: point.y, trustworthy: mismatch < 100)
            },
            convoyFit: {
                points,
                enabled,
                userPoint,
                followSelfEnabled,
                resumeSelfFollow,
                restoreViewport in
                let state = map.cameraState
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
                        latitude: state.center.latitude,
                        longitude: state.center.longitude,
                        zoom: state.zoom,
                        bearing: state.bearing,
                        pitch: state.pitch
                    )
                }
                let coordinates = points.map {
                    CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                }
                guard let camera = try? map.camera(
                    for: coordinates,
                    camera: CameraOptions(
                        bearing: state.bearing,
                        pitch: MapHomeConvoyViewportPolicy.fitComputationPitch
                    ),
                    coordinatesPadding: UIEdgeInsets(top: 110, left: 60, bottom: 150, right: 60),
                    maxZoom: MapHomeConvoyViewportPolicy.maximumFitZoom,
                    offset: nil
                ) else { return }
                performProgrammaticViewportChange {
                    // The fit is solved on a flat camera, but the final viewport
                    // keeps the LIVE pitch so convoy focus does not silently drop
                    // the user's current 2D/3D framing.
                    withViewportAnimation(.easeInOut(duration: 0.9)) {
                        viewport.wrappedValue = .camera(
                            center: camera.center ?? state.center,
                            zoom: MapHomeConvoyViewportPolicy.fitZoom(
                                camera.zoom, fallback: state.zoom
                            ),
                            bearing: state.bearing,
                            pitch: state.pitch
                        )
                    }
                }
            },
            center: { point in
                if meFollowEnabled.wrappedValue {
                    meFollowSuspended.wrappedValue = true
                }
                let state = map.cameraState
                performProgrammaticViewportChange {
                    withViewportAnimation(.easeInOut(duration: 0.9)) {
                        viewport.wrappedValue = .camera(
                            center: CLLocationCoordinate2D(
                                latitude: point.latitude, longitude: point.longitude
                            ),
                            zoom: state.zoom,
                            bearing: state.bearing,
                            pitch: state.pitch
                        )
                    }
                }
            }
        )
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
        }
        change()
    }

    private func armMeFollowIdleReturn(viewport: Binding<Viewport>) {
        meFollowIdleTask?.cancel()
        guard surfaceActive, meFollowEnabled, meFollowSuspended else {
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
                  surfaceActive, meFollowEnabled, meFollowSuspended
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
        performProgrammaticViewportChange {
            withViewportAnimation(.easeInOut(duration: 0.9)) {
                viewport.wrappedValue = .camera(
                    center: camera.center,
                    zoom: camera.zoom,
                    bearing: camera.bearing,
                    pitch: camera.pitch
                )
            }
        }
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
            restoringBrowsing: restoringBrowsing
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
