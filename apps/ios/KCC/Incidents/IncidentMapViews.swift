import SwiftUI

struct IncidentMapOverlay: View {
    @Bindable var coordinator: IncidentMapCoordinator
    let projection: any MapProjection

    var body: some View {
        GeometryReader { geometry in
            let markers = coordinator.incidents.map(\.mapMarker)
                + PoliceMapMarker.markers(
                    pins: coordinator.policeReports,
                    incidents: coordinator.incidents
                )
            let importedMarkerVisible = coordinator.incidents.contains { incident in
                guard incident.isImported else { return false }
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
                        .accessibilityLabel(Text("incidents.markerLabel"))
                        .position(x: CGFloat(point.x), y: CGFloat(point.y))
                    }
                }
                if importedMarkerVisible {
                    Text("incidents.sourceTrafikverket")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.primary)
                        .padding(.horizontal, KccSpacing.s3)
                        .padding(.vertical, KccSpacing.s2)
                        .background(.regularMaterial, in: Capsule())
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
                            HStack(spacing: KccSpacing.s3) {
                                Image(systemName: type.symbolName)
                                    .foregroundStyle(Color(argb: type.markerColorArgb))
                                    .frame(width: 30)
                                Text(LocalizedStringKey(type.titleKey))
                                Spacer()
                                if selectedType == type { Image(systemName: "checkmark") }
                            }
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
                            Label("incidents.reportLocationCurrent", systemImage: "location.fill")
                        }
                        .disabled(coordinator.busy)

                        Button {
                            coordinator.beginMapSelection(for: selectedType)
                            dismiss()
                        } label: {
                            Label("incidents.reportLocationPick", systemImage: "mappin.and.ellipse")
                        }
                        .disabled(coordinator.busy)
                    }
                }
            }
            .navigationTitle("incidents.reportTitle")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("incidents.cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

struct IncidentLocationPickerControls: View {
    let canConfirm: Bool
    let confirm: () -> Void
    let cancel: () -> Void

    var body: some View {
        VStack(spacing: KccSpacing.s3) {
            Text("incidents.pickLocationInstruction")
                .font(.callout.weight(.semibold))
                .padding(.horizontal, KccSpacing.s3)
                .padding(.vertical, KccSpacing.s2)
                .background(.regularMaterial, in: Capsule())
            Spacer()
            HStack(spacing: KccSpacing.s3) {
                Button("incidents.cancel", role: .cancel, action: cancel)
                    .buttonStyle(.bordered)
                Button("incidents.pickLocationConfirm", action: confirm)
                    .buttonStyle(.borderedProminent)
                    .disabled(!canConfirm)
            }
            .padding(KccSpacing.s4)
            .background(.regularMaterial, in: Capsule())
        }
        .padding(.top, KccSpacing.s12)
        .padding(.bottom, KccSpacing.s12 + KccSpacing.s12)
        .overlay {
            Image(systemName: "mappin")
                .font(.system(size: 42, weight: .bold))
                .foregroundStyle(.red)
                .shadow(color: .white, radius: 2)
                .offset(y: -20)
                .allowsHitTesting(false)
        }
    }
}

struct IncidentDetailsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var coordinator: IncidentMapCoordinator

    var body: some View {
        NavigationStack {
            if let incident = coordinator.selectedIncident {
                List {
                    Section {
                        Label(
                            LocalizedStringKey(incident.type.titleKey),
                            systemImage: incident.type.symbolName
                        )
                        .font(.headline)
                        if let note = incident.note, !note.isEmpty { Text(note) }
                        Text(incident.isImported ? "incidents.sourceImported" : "incidents.sourceMember")
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
                    Section {
                        if coordinator.selectedIncidentOwned {
                            Button("incidents.removeAction", role: .destructive) {
                                Task { await coordinator.removeSelectedIncident() }
                            }
                        } else if incident.isImported {
                            Text("incidents.removeImportedExplanation")
                                .foregroundStyle(.secondary)
                        } else {
                            Button("incidents.stillHereYes") {
                                Task { await coordinator.confirmSelectedIncident() }
                            }
                            Button("incidents.stillHereNo") {
                                Task { await coordinator.clearSelectedIncident() }
                            }
                        }
                    } header: {
                        Text("incidents.stillHereQuestion")
                    }
                }
                .disabled(coordinator.busy)
                .navigationTitle("incidents.markerLabel")
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("incidents.close") { dismiss() }
                    }
                }
            }
        }
        .presentationDetents([.medium])
        .alert(
            LocalizedStringKey(coordinator.feedbackKey ?? "incidents.verifyError"),
            isPresented: feedbackPresented
        ) {
            Button("notifications.errorDismiss", role: .cancel) { coordinator.clearFeedback() }
        }
    }

    private var feedbackPresented: Binding<Bool> {
        Binding(
            get: { coordinator.feedback != nil },
            set: { if !$0 { coordinator.clearFeedback() } }
        )
    }
}

struct PoliceDetailsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var coordinator: IncidentMapCoordinator

    var body: some View {
        NavigationStack {
            if let pin = coordinator.selectedPolice {
                List {
                    Section {
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
                    Section {
                        if pin.mine {
                            Button("police.removeAction", role: .destructive) {
                                Task { await coordinator.removeSelectedPolice() }
                            }
                        } else {
                            Button("police.stillHereYes") {
                                Task { await coordinator.verifySelectedPolice(confirm: true) }
                            }
                            Button("police.stillHereNo") {
                                Task { await coordinator.verifySelectedPolice(confirm: false) }
                            }
                        }
                    } header: {
                        Text("police.stillHereQuestion")
                    }
                }
                .disabled(coordinator.busy)
                .navigationTitle("police.sheetTitle")
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("incidents.close") { dismiss() }
                    }
                }
            }
        }
        .presentationDetents([.medium])
        .alert(
            LocalizedStringKey(coordinator.feedbackKey ?? "police.verifyError"),
            isPresented: feedbackPresented
        ) {
            Button("notifications.errorDismiss", role: .cancel) { coordinator.clearFeedback() }
        }
    }

    private var feedbackPresented: Binding<Bool> {
        Binding(
            get: { coordinator.feedback != nil },
            set: { if !$0 { coordinator.clearFeedback() } }
        )
    }
}

struct PoliceProximityBanner: View {
    let dismiss: () -> Void

    var body: some View {
        HStack(spacing: KccSpacing.s3) {
            Image(systemName: "shield.fill")
                .font(.title2)
                .foregroundStyle(.white)
            Text("policeAlert.caption")
                .font(.headline)
                .foregroundStyle(.white)
            Spacer()
            Button(action: dismiss) {
                Image(systemName: "xmark").foregroundStyle(.white)
            }
            .accessibilityLabel(Text("incidents.close"))
        }
        .padding(KccSpacing.s4)
        .background(Color.blue.opacity(0.94), in: RoundedRectangle(cornerRadius: 16))
        .shadow(radius: 8)
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
