import SwiftUI
import UIKit

struct IncidentMapOverlay: View {
    @Bindable var coordinator: IncidentMapCoordinator
    let projection: any MapProjection
    var onSelect: ((String) -> Void)? = nil

    var body: some View {
        GeometryReader { geometry in
            let markers = IncidentLayerPresentation.markers(
                enabled: coordinator.trafficAlertsEnabled,
                incidents: coordinator.incidents,
                policeReports: coordinator.policeReports
            )
            let importedMarkerVisible = IncidentLayerPresentation.importedIncidents(
                enabled: coordinator.trafficAlertsEnabled,
                incidents: coordinator.incidents
            ).contains { incident in
                let point = projection.screenPositionFor(
                        latitude: incident.latitude, longitude: incident.longitude
                      )
                return IncidentAttribution.markerIsVisible(
                    point,
                    width: Double(geometry.size.width),
                    height: Double(geometry.size.height)
                )
            }
            ZStack(alignment: .bottomLeading) {
                ForEach(markers, id: \.id) { marker in
                    if let point = projection.screenPositionFor(
                        latitude: marker.latitude, longitude: marker.longitude
                    ), point.trustworthy,
                       point.x >= -30, point.y >= -30,
                       point.x <= Double(geometry.size.width + 30),
                       point.y <= Double(geometry.size.height + 30) {
                        Button {
                            coordinator.selectMarker(id: marker.id)
                            onSelect?(marker.id)
                        } label: {
                            ZStack {
                                Circle()
                                    .fill(Color(argb: marker.colorArgb))
                                    .frame(width: 38, height: 38)
                                    .shadow(radius: 2, y: 1)
                                Image(systemName: marker.iconName)
                                    .font(.system(size: 17, weight: .bold))
                                    .foregroundStyle(Color(argb: marker.glyphColorArgb))
                                if marker.reportedCleared {
                                    Capsule().fill(Color.white.opacity(0.9))
                                        .frame(width: 34, height: 3)
                                        .rotationEffect(.degrees(-38))
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                        .accessibilityLabel(Text(IncidentAccessibility.markerLabel(
                            id: marker.id,
                            incidents: coordinator.incidents,
                            policeReports: coordinator.policeReports
                        )))
                        .position(x: CGFloat(point.x), y: CGFloat(point.y))
                    }
                }
                if importedMarkerVisible {
                    Text("incidents.sourceTrafikverket")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.primary)
                        .padding(.horizontal, KccSpacing.s3)
                        .padding(.vertical, KccSpacing.s2)
                        .background(KccTheme.surface, in: RoundedRectangle(cornerRadius: KccRadius.md))
                        .padding(KccSpacing.s4)
                        .accessibilityIdentifier("incident-trafikverket-attribution")
                        .allowsHitTesting(false)
                }
            }
            .frame(
                width: geometry.size.width,
                height: geometry.size.height,
                alignment: .bottomLeading
            )
        }
        .allowsHitTesting(true)
    }
}

struct IncidentReportSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var coordinator: IncidentMapCoordinator
    @State private var selectedType: IncidentType?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("incidents.reportSubtitle")
                        .foregroundStyle(.secondary)
                }
                Section {
                    ForEach(IncidentType.allCases, id: \.self) { type in
                        Button {
                            selectedType = type
                        } label: {
                            IncidentTypeChoice(type: type, selected: selectedType == type)
                        }
                    }
                }
                if let selectedType {
                    Section("incidents.reportLocationTitle") {
                        Button {
                            Task {
                                await coordinator.report(selectedType, at: nil)
                                dismiss()
                            }
                        } label: {
                            Label("incidents.reportLocationCurrent", systemImage: "location.fill").frame(maxWidth: .infinity, minHeight: 48)
                        }
                        .disabled(coordinator.busy)

                        Button {
                            coordinator.beginMapSelection(for: selectedType)
                            dismiss()
                        } label: {
                            Label("incidents.reportLocationPick", systemImage: "mappin.and.ellipse").frame(maxWidth: .infinity, minHeight: 48)
                        }
                        .disabled(coordinator.busy)
                    }
                }
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle("incidents.reportTitle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text("incidents.cancel").frame(minHeight: 48) }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

struct IncidentLocationPickerControls: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    let canConfirm: Bool
    let confirm: () -> Void
    let cancel: () -> Void

    var body: some View {
        GeometryReader { geometry in
            let compact = typeSize.isAccessibilitySize
            let short = geometry.size.height < 650
            VStack(spacing: KccSpacing.s3) {
                ScrollView {
                    Text("incidents.pickLocationInstruction").font(.callout.weight(.semibold))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(height: compact && short ? 56 : (typeSize.isAccessibilitySize ? 112 : 64))
                .padding(KccSpacing.s3)
                .background(KccTheme.surface, in: RoundedRectangle(cornerRadius: KccRadius.md))
                Spacer(minLength: KccSpacing.s2)
                commands(compact: compact)
            }
            .padding(.top, short ? KccSpacing.s4 : KccSpacing.s12)
            .padding(.bottom, KccSpacing.s12 + KccSpacing.s12)
            .overlay {
                Image(systemName: "mappin").font(.system(size: 42, weight: .bold))
                    .foregroundStyle(KccTheme.error).shadow(color: .white, radius: 2)
                    .offset(y: -20).allowsHitTesting(false)
            }
        }
    }

    private func commands(compact: Bool) -> some View {
        HStack(spacing: KccSpacing.s3) {
            Button(role: .cancel, action: cancel) {
                Group {
                    if compact { Image(systemName: "xmark").font(.system(size: 20, weight: .semibold)) }
                    else { Text("incidents.cancel") }
                }.frame(minWidth: 48, minHeight: 48)
            }.buttonStyle(.bordered).accessibilityLabel(Text("incidents.cancel"))
            Button(action: confirm) {
                Group {
                    if compact { Image(systemName: "checkmark").font(.system(size: 20, weight: .semibold)) }
                    else { Text("incidents.pickLocationConfirm") }
                }.frame(minWidth: 48, minHeight: 48)
            }.kccPrimaryAction().disabled(!canConfirm)
                .accessibilityLabel(Text("incidents.pickLocationConfirm"))
        }
        .frame(maxWidth: .infinity).padding(KccSpacing.s4)
        .background(KccTheme.surface, in: RoundedRectangle(cornerRadius: KccRadius.md))
    }
}

struct IncidentDetailsContent: View {
    @Bindable var coordinator: IncidentMapCoordinator

    var body: some View {
        if let incident = coordinator.selectedIncident {
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                Group {
                    SocialFlow {
                        Image(systemName: incident.type.symbolName).font(.system(size: 24)).accessibilityHidden(true)
                        Text(LocalizedStringKey(incident.type.titleKey)).font(.headline).fixedSize(horizontal: false, vertical: true)
                    }
                    if let note = incident.note, !note.isEmpty { Text(note) }
                    Text(incident.isImported ? "incidents.sourceImported" : "incidents.sourceMember")
                        .foregroundStyle(.secondary)
                    Text(IncidentAge.localizedDescription(for: incident))
                        .foregroundStyle(.secondary)
                    if incident.confirmationCount > 0 {
                        Text(String.localizedStringWithFormat(
                            NSLocalizedString("incidents.confirmedBy", comment: ""),
                            incident.confirmationCount
                        ))
                    }
                    if incident.clearedCount > 0 {
                        Text(String.localizedStringWithFormat(
                            NSLocalizedString("incidents.clearedBy", comment: ""),
                            incident.clearedCount
                        ))
                    }
                    if incident.reportedCleared {
                        Label("incidents.clearedMarkerHint", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                    }
                }
                Text("incidents.stillHereQuestion").font(.headline)
                Group {
                    if coordinator.selectedIncidentOwned {
                        Button(role: .destructive) { Task { await coordinator.removeSelectedIncident() } } label: { Text("incidents.removeAction").frame(maxWidth: .infinity, minHeight: 48) }.tint(KccTheme.error)
                    } else if incident.isImported {
                        Text("incidents.removeImportedExplanation")
                            .foregroundStyle(.secondary)
                    } else {
                        Button { Task { await coordinator.confirmSelectedIncident() } } label: { Text("incidents.stillHereYes").frame(maxWidth: .infinity, minHeight: 48) }
                        Button { Task { await coordinator.clearSelectedIncident() } } label: { Text("incidents.stillHereNo").frame(maxWidth: .infinity, minHeight: 48) }
                    }
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .font(.body).foregroundStyle(KccTheme.text).tint(KccTheme.driver)
            .disabled(coordinator.busy)
        }
    }
}

struct PoliceDetailsContent: View {
    @Bindable var coordinator: IncidentMapCoordinator

    var body: some View {
        if let pin = coordinator.selectedPolice {
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                Group {
                    Label("police.reportedByMember", systemImage: "shield.fill")
                    if pin.confirmationCount > 0 {
                        Text(String.localizedStringWithFormat(
                            NSLocalizedString("police.confirmedBy", comment: ""),
                            pin.confirmationCount
                        ))
                    }
                    if pin.disputeCount > 0 {
                        Text(String.localizedStringWithFormat(
                            NSLocalizedString("police.disputedBy", comment: ""),
                            pin.disputeCount
                        ))
                    }
                    if pin.mine { Text("police.ownPinHint").foregroundStyle(.secondary) }
                }
                Text("police.stillHereQuestion").font(.headline)
                Group {
                    if pin.mine {
                        Button(role: .destructive) { Task { await coordinator.removeSelectedPolice() } } label: { Text("police.removeAction").frame(maxWidth: .infinity, minHeight: 48) }.tint(KccTheme.error)
                    } else {
                        Button { Task { await coordinator.verifySelectedPolice(confirm: true) } } label: { Text("police.stillHereYes").frame(maxWidth: .infinity, minHeight: 48) }
                        Button { Task { await coordinator.verifySelectedPolice(confirm: false) } } label: { Text("police.stillHereNo").frame(maxWidth: .infinity, minHeight: 48) }
                    }
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .font(.body).foregroundStyle(KccTheme.text).tint(KccTheme.driver)
            .disabled(coordinator.busy)
        }
    }
}

struct PoliceProximityBanner: View {
    let dismiss: () -> Void

    var body: some View {
        HStack(spacing: KccSpacing.s3) {
            Image(systemName: "shield.fill")
                .font(.system(size: 20)).accessibilityHidden(true)
                .foregroundStyle(.white)
            Text("policeAlert.caption")
                .font(.headline)
                .foregroundStyle(.white)
            Spacer()
            Button(action: dismiss) {
                Image(systemName: "xmark").font(.system(size: 20)).foregroundStyle(.white).frame(width: 48, height: 48).contentShape(Rectangle())
            }
            .accessibilityLabel(Text("incidents.close"))
        }
        .padding(KccSpacing.s4)
        .background(Color.blue.opacity(0.94), in: RoundedRectangle(cornerRadius: 16))
        .shadow(radius: 8)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits([.isModal, .updatesFrequently])
        .onAppear {
            UIAccessibility.post(
                notification: .announcement,
                argument: NSLocalizedString("policeAlert.caption", comment: "")
            )
        }
    }
}

private extension Color {
    init(argb: UInt32) {
        self.init(
            red: Double((argb >> 16) & 0xFF) / 255,
            green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255,
            opacity: Double((argb >> 24) & 0xFF) / 255
        )
    }
}

struct IncidentTypeChoice: View {
    let type: IncidentType
    let selected: Bool
    var body: some View {
        HStack(spacing: KccSpacing.s3) {
            Image(systemName: type.symbolName).font(.system(size: 20)).foregroundStyle(Color(argb: type.markerColorArgb))
                .frame(width: 30).accessibilityHidden(true)
            Text(LocalizedStringKey(type.titleKey)).font(.body).fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            if selected { Image(systemName: "checkmark").font(.system(size: 18, weight: .semibold)).foregroundStyle(KccTheme.driver).accessibilityHidden(true) }
        }.frame(maxWidth: .infinity, minHeight: 48, alignment: .leading).contentShape(Rectangle())
            .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
