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
                .kccMapControl()
        }
        .font(.system(size: 20, weight: .semibold))
        .accessibilityLabel(Text("shell.layersButton"))
        .accessibilityHint(Text("shell.layersTitle"))
    }
}

struct MapLayersSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var focusExpanded = false

    let preferences: MapLayerPreferences
    let systemIsDark: Bool
    let trafikverketDataShown: Bool
    let onTrafficAlertsChanged: (Bool) -> Void
    let onTrafficChanged: (Bool) -> Void
    let onMapModeChanged: (MapMode) -> Void
    let on3DChanged: (Bool) -> Void
    let onBrowsingZoomChanged: (Double) -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    layerRow("shell.layersTraffic", help: "shell.layersTrafficHelp",
                             icon: "car", binding: trafficBinding, id: "shell.layersTraffic")
                    Divider()
                    layerRow("shell.layersIncidents", help: "shell.layersIncidentsHelp",
                             icon: "exclamationmark.triangle", binding: trafficAlertsBinding,
                             id: "shell.layersIncidents")
                    if preferences.trafficAlertsEnabled && trafikverketDataShown {
                        Text("incidents.sourceTrafikverket")
                            .font(.footnote)
                            .foregroundStyle(KccTheme.secondary)
                    }
                    Divider()
                    layerRow("shell.layers3d", help: "shell.layers3dHelp",
                             icon: "cube", binding: threeDBinding, id: "shell.layers3d")
                    Divider()
                    appearanceControls
                    Button { focusExpanded.toggle() } label: {
                        HStack(spacing: KccSpacing.s2) {
                            Text(focusExpanded ? LocalizedStringKey("shell.layersZoomCollapse") : LocalizedStringKey("shell.layersZoomExpand"))
                                .font(.body)
                            Spacer(minLength: KccSpacing.s2)
                            Image(systemName: focusExpanded ? "chevron.up" : "chevron.down")
                                .font(.caption.weight(.semibold))
                                .accessibilityHidden(true)
                        }
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("shell.layersFocus")
                    // Keep the slider outside the expandable button's AX subtree:
                    // a container identifier must not replace the child's identity.
                    if focusExpanded {
                        VStack(alignment: .leading, spacing: KccSpacing.s3) {
                            Slider(
                                value: zoomBinding,
                                in: MapBrowsingZoom.minimum...MapBrowsingZoom.maximum,
                                step: MapBrowsingZoom.step
                            ) {
                                Text("shell.layersZoomTitle")
                            } minimumValueLabel: {
                                Text("shell.layersZoomFar").font(.caption)
                            } maximumValueLabel: {
                                Text("shell.layersZoomNear").font(.caption)
                            }
                            .accessibilityValue(Text(zoomAccessibilityValue))
                            .accessibilityIdentifier("shell.layersZoom")
                            Text("shell.layersZoomHelp")
                                .font(.subheadline)
                                .foregroundStyle(KccTheme.secondary)
                        }
                        .padding(.top, KccSpacing.s3)
                    }
                }
                .padding(KccSpacing.s4)
            }
            .accessibilityIdentifier("shell.layers.content")
            .tint(KccTheme.driver)
            .kccReadingSurface()
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

    private var trafficAlertsBinding: Binding<Bool> {
        Binding(
            get: { preferences.trafficAlertsEnabled },
            set: { enabled in
                preferences.setTrafficAlertsEnabled(enabled)
                onTrafficAlertsChanged(enabled)
            }
        )
    }

    private func layerRow(
        _ title: LocalizedStringKey, help: LocalizedStringKey,
        icon: String, binding: Binding<Bool>, id: String
    ) -> some View {
        Toggle(isOn: binding) {
            HStack(alignment: .top, spacing: KccSpacing.s3) {
                Image(systemName: icon)
                    .font(.title3)
                    .frame(width: 28)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: KccSpacing.s1) {
                    Text(title).font(.body)
                    Text(help)
                        .font(.subheadline)
                        .foregroundStyle(KccTheme.secondary)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(minHeight: 48)
        .accessibilityIdentifier(id)
    }

    private var appearanceControls: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text("shell.layersAppearanceTitle")
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            // Never shrink labels to fit the three-column catalogue layout.
            // Accessibility text stacks full-width choices instead.
            let layout = dynamicTypeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(spacing: KccSpacing.s2))
                : AnyLayout(HStackLayout(spacing: KccSpacing.s2))
            layout { appearanceChoices }
        }
    }

    @ViewBuilder private var appearanceChoices: some View {
        appearanceChoice("shell.layersAppearanceDay", icon: "sun.max", mode: .day, id: "day")
        appearanceChoice("shell.layersAppearanceNight", icon: "moon", mode: .night, id: "night")
        appearanceChoice("shell.layersAppearanceSystem", icon: "gearshape", mode: nil, id: "system")
    }

    private func appearanceChoice(
        _ title: LocalizedStringKey, icon: String, mode: MapMode?, id: String
    ) -> some View {
        let selected = preferences.mapModeOverride == mode
        return Button {
            onMapModeChanged(preferences.selectMapAppearance(mode, systemIsDark: systemIsDark))
        } label: {
            Label(title, systemImage: icon)
                .font(.body)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: 48)
                .padding(.horizontal, KccSpacing.s2)
                .contentShape(Rectangle())
                .foregroundStyle(selected ? KccTheme.driver : KccTheme.text)
                .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.sm))
                .overlay {
                    RoundedRectangle(cornerRadius: KccRadius.sm)
                        .strokeBorder(selected ? KccTheme.driver : KccTheme.border, lineWidth: selected ? 2 : 1)
                }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("shell.layersAppearance.\(id)")
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
