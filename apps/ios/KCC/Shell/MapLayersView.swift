import SwiftUI

/// Accessible map-layers control for the map home. The sheet remains useful in
/// config-less builds because it updates the same observable surface stub; a
/// configured Mapbox renderer applies those commands immediately.
struct MapLayersButton: View {
    @Binding var isPresented: Bool

    var body: some View {
        Button {
            isPresented = true
        } label: {
            Image(systemName: "square.3.layers.3d")
                .frame(width: 48, height: 48)
                .background(.regularMaterial, in: Circle())
        }
        .font(.system(size: 20, weight: .semibold))
        .accessibilityLabel(Text("shell.layersButton"))
        .accessibilityHint(Text("shell.layersTitle"))
    }
}

struct MapLayersSheet: View {
    @Environment(\.dismiss) private var dismiss

    let preferences: MapLayerPreferences
    let systemIsDark: Bool
    let onTrafficChanged: (Bool) -> Void
    let onMapModeChanged: (MapMode) -> Void
    let on3DChanged: (Bool) -> Void
    let onBrowsingZoomChanged: (Double) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("shell.layersTraffic", isOn: trafficBinding)
                    Toggle("shell.layersNightMode", isOn: nightBinding)
                    Toggle("shell.layers3d", isOn: threeDBinding)
                }

                Section {
                    Slider(
                        value: zoomBinding,
                        in: MapBrowsingZoom.minimum...MapBrowsingZoom.maximum,
                        step: MapBrowsingZoom.step
                    ) {
                        Text("shell.layersZoomTitle")
                    } minimumValueLabel: {
                        Text("shell.layersZoomFar")
                            .font(.caption)
                    } maximumValueLabel: {
                        Text("shell.layersZoomNear")
                            .font(.caption)
                    }
                    .accessibilityValue(Text(zoomAccessibilityValue))

                    Text("shell.layersZoomHelp")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("shell.layersZoomTitle")
                }
            }
            .navigationTitle("shell.layersTitle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("shell.layersClose") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var trafficBinding: Binding<Bool> {
        Binding(
            get: { preferences.trafficEnabled },
            set: { enabled in
                preferences.setTrafficEnabled(enabled)
                onTrafficChanged(enabled)
            }
        )
    }

    private var nightBinding: Binding<Bool> {
        Binding(
            get: { preferences.effectiveMapMode(systemIsDark: systemIsDark) == .night },
            set: { enabled in
                let mode: MapMode = enabled ? .night : .day
                preferences.setMapModeOverride(mode)
                onMapModeChanged(mode)
            }
        )
    }

    private var threeDBinding: Binding<Bool> {
        Binding(
            get: { preferences.is3D },
            set: { enabled in
                preferences.set3DEnabled(enabled)
                on3DChanged(enabled)
            }
        )
    }

    private var zoomBinding: Binding<Double> {
        Binding(
            get: { preferences.browsingZoom },
            set: { zoom in
                preferences.setBrowsingZoom(zoom)
                onBrowsingZoomChanged(preferences.browsingZoom)
            }
        )
    }

    private var zoomAccessibilityValue: String {
        String(format: "%.1f", preferences.browsingZoom)
    }
}
