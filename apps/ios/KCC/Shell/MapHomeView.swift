import SwiftUI

/// The map home: hosts the shell's single map surface and the minimal chrome
/// the current slice carries (the "Loading roads…" chip). The floating
/// controls, search bar and bottom chrome from Android's `shell/MapHome.kt`
/// arrive with their own slices — this is deliberately only the surface host,
/// not a port of the map-home chrome.
///
/// Typed against ``StubMapSurface`` (not the ``MapSurface`` protocol) on
/// purpose, mirroring the seam's documented deviation: the seam has no view
/// requirement yet, so RENDERING a surface is stub-specific until the real
/// map-UI PR adds `Content` to the protocol and this view swaps to it. The
/// placeholder rendering mirrors the stub's Android `Content` — a flat
/// surface-variant field with the `shell.mapPlaceholder` label centred.
struct MapHomeView: View {
    /// The shell's one surface instance, owned by ``ShellView`` — composed
    /// once for the whole signed-in shell and never disposed.
    let surface: StubMapSurface

    var body: some View {
        ZStack {
            Color(.secondarySystemBackground)
                .ignoresSafeArea()
            Text("shell.mapPlaceholder")
                .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.medium))
                .foregroundStyle(.secondary)
        }
        // Load-state chip, mirroring Android's `LoadingRoadsChip`: visible
        // while the (simulated) tiles load, gone once the map is interactive.
        .overlay(alignment: .top) {
            if surface.loadState == .loading {
                loadingRoadsChip
                    .padding(.top, KccSpacing.s4)
            }
        }
        // The iOS analogue of the `LaunchedEffect` Android's stub content
        // runs: simulate the short tile load once, so "Loading roads…"
        // appears briefly then clears. Idempotent after the first load, so
        // re-entering the map tab does not flash the chip again.
        .task { await surface.simulateInitialLoadIfNeeded() }
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

#Preview("Loading") {
    MapHomeView(surface: StubMapSurface(initialState: .loading, autoLoad: false))
}

#Preview("Loaded") {
    MapHomeView(surface: StubMapSurface(initialState: .loaded, autoLoad: false))
}
