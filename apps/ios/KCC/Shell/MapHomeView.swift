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
                surface.updateCameraSnapshot(.of(
                    latitude: state.center.latitude,
                    longitude: state.center.longitude,
                    zoom: state.zoom,
                    bearing: state.bearing,
                    pitch: state.pitch
                ))
            }
            .onDisappear {
                proxy.viewport?.removeStatusObserver(interactionObserver)
                surface.removeRenderer()
            }
            .onAppear {
                interactionObserver.onUserInteraction = { meFollowSuspended = true }
            }
            .ignoresSafeArea()
        }
        .task(id: "\(surface.isActive)-\(meFollowEnabled)") {
            guard surface.isActive, meFollowEnabled else { return }
            for await fix in locationProvider.fixes() {
                if Task.isCancelled { return }
                let point = MapPoint(longitude: fix.longitude, latitude: fix.latitude)
                latestOwnPoint = point
                guard meFollowEnabled, !meFollowSuspended else { continue }
                applyMeFollow(point, viewport: $viewport)
            }
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
            convoyFit: { points, enabled, userPoint, followSelfEnabled in
                let state = map.cameraState
                meFollowEnabled.wrappedValue = !enabled && followSelfEnabled
                switch MapHomeConvoyViewportPolicy.plan(points: points, focusEnabled: enabled) {
                case .keepCurrentViewport:
                    return
                case .restoreBrowsing:
                    meFollowSuspended.wrappedValue = false
                    let fallback = cameraBeforeConvoy.wrappedValue
                    cameraBeforeConvoy.wrappedValue = nil
                    latestOwnPoint.wrappedValue = latestOwnPoint.wrappedValue ?? userPoint
                    let followPoint = latestOwnPoint.wrappedValue ?? userPoint
                    withViewportAnimation(.easeInOut(duration: 0.9)) {
                        viewport.wrappedValue = .camera(
                            center: followPoint.map {
                                CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                            } ?? fallback.map {
                                CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                            } ?? state.center,
                            zoom: followPoint == nil
                                ? (fallback?.zoom ?? state.zoom)
                                : StubMapSurface.defaultBrowsingZoom,
                            bearing: state.bearing,
                            pitch: state.pitch
                        )
                    }
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
                withViewportAnimation(.easeInOut(duration: 0.9)) {
                    // The fit is solved on a flat camera, but the final viewport
                    // keeps the LIVE pitch so convoy focus does not silently drop
                    // the user's current 2D/3D framing.
                    viewport.wrappedValue = .camera(
                        center: camera.center ?? state.center,
                        zoom: MapHomeConvoyViewportPolicy.fitZoom(
                            camera.zoom, fallback: state.zoom
                        ),
                        bearing: state.bearing,
                        pitch: state.pitch
                    )
                }
            },
            center: { point in
                let state = map.cameraState
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
        )
    }

    private func applyMeFollow(_ point: MapPoint, viewport: Binding<Viewport>) {
        let snapshot = surface.cameraSnapshot
        withViewportAnimation(.easeInOut(duration: 0.9)) {
            viewport.wrappedValue = .camera(
                center: CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude),
                zoom: snapshot?.zoom ?? StubMapSurface.defaultBrowsingZoom,
                bearing: snapshot?.bearing ?? 0,
                pitch: snapshot?.pitch ?? 45
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
