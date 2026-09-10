import SwiftUI
import MapboxMaps

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
                MapboxStandardMap(accessToken: accessToken) {
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

    init(accessToken: String, onLoaded: @escaping @MainActor () -> Void) {
        MapboxOptions.accessToken = accessToken
        self.onLoaded = onLoaded
    }

    var body: some View {
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
        .ignoresSafeArea()
    }
}

#Preview("Loading") {
    MapHomeView(surface: StubMapSurface(initialState: .loading, autoLoad: false))
}

#Preview("Loaded") {
    MapHomeView(surface: StubMapSurface(initialState: .loaded, autoLoad: false))
}
