import SwiftUI
import MapboxMaps
import CoreLocation
import UIKit

/// Hosts the shell's single Mapbox Standard map. Config-less builds keep the
/// deterministic placeholder, while a valid public token swaps in the native
/// renderer without changing the shell-facing ``MapSurface`` seam.
struct MapHomeView: View {
    /// The shell's one surface instance, owned by ``ShellView`` — composed
    /// once for the whole signed-in shell and never disposed.
    let surface: StubMapSurface
    private let accessToken: String?

    init(
        surface: StubMapSurface,
        accessToken: String? = MapboxConfiguration.accessToken()
    ) {
        self.surface = surface
        self.accessToken = accessToken
    }

    var body: some View {
        ZStack {
            if let accessToken {
                MapboxStandardMap(accessToken: accessToken, surface: surface) {
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
private struct MapboxStandardMap: View {
    let onLoaded: @MainActor () -> Void
    let surface: StubMapSurface

    init(
        accessToken: String,
        surface: StubMapSurface,
        onLoaded: @escaping @MainActor () -> Void
    ) {
        MapboxOptions.accessToken = accessToken
        self.surface = surface
        self.onLoaded = onLoaded
    }

    var body: some View {
        MapReader { proxy in
            MapboxMaps.Map(
                initialViewport: .camera(
                    center: .init(latitude: 57.4872, longitude: 12.0761),
                    zoom: StubMapSurface.defaultBrowsingZoom,
                    bearing: 0,
                    pitch: 45
                )
            )
            .mapStyle(.standard)
            .onMapLoaded { _ in onLoaded() }
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
            .onAppear { installRenderer(proxy.map) }
            .onDisappear { surface.removeRenderer() }
            .ignoresSafeArea()
        }
    }

    private func installRenderer(_ map: MapboxMap?) {
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
            convoyFit: { points, enabled in
                guard enabled, let points, points.count >= 2 else { return }
                let coordinates = points.map {
                    CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                }
                guard let camera = try? map.camera(
                    for: coordinates,
                    camera: CameraOptions(pitch: 0),
                    coordinatesPadding: UIEdgeInsets(top: 110, left: 60, bottom: 150, right: 60),
                    maxZoom: 16,
                    offset: nil
                ) else { return }
                map.setCamera(to: camera)
            },
            center: { point in
                map.setCamera(to: CameraOptions(
                    center: CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude)
                ))
            }
        )
    }
}

#Preview("Loading") {
    MapHomeView(surface: StubMapSurface(initialState: .loading, autoLoad: false))
}

#Preview("Loaded") {
    MapHomeView(surface: StubMapSurface(initialState: .loaded, autoLoad: false))
}
