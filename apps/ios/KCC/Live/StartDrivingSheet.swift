import SwiftUI

/// The iOS port of Android's Create chooser. It observes the
/// owner's garage only while presented, preselects the main/first vehicle,
/// and returns the selected id to either the Single-session or Convoy flow.
/// Starting with no vehicle remains valid: the backend then uses its fallback.
struct StartDrivingSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var garage: GarageCoordinator
    @State private var selectedVehicleId: String?
    @State private var rankedVehicleIds: [String]?
    @State private var showingAllVehicles = false
    @State private var detent: PresentationDetent = .medium

    let isStarting: Bool
    let canStartSingleSession: Bool
    let onStart: (String?) -> Void
    let onConvoy: (String?) -> Void

    init(
        garage: GarageCoordinator,
        isStarting: Bool,
        canStartSingleSession: Bool,
        onStart: @escaping (String?) -> Void,
        onConvoy: @escaping (String?) -> Void
    ) {
        _garage = State(initialValue: garage)
        _selectedVehicleId = State(initialValue: nil)
        self.isStarting = isStarting
        self.canStartSingleSession = canStartSingleSession
        self.onStart = onStart
        self.onConvoy = onConvoy
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    Text("shell.createChooserBody")
                        .font(.body)
                        .foregroundStyle(KccTheme.secondary)

                    vehiclePicker

                    Button {
                        let chosen = effectiveVehicleId
                        dismiss()
                        onStart(chosen)
                    } label: {
                        SocialFlow {
                            Image(systemName: "car.fill").font(.system(size: 24)).accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                                Text("shell.createChooserSingle")
                                    .font(.headline)
                                Text("shell.createChooserSingleBody")
                                    .font(.subheadline)
                            }
                            SocialFlowSpacer()
                        }
                        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    }
                    .kccPrimaryAction()
                    .accessibilityIdentifier("shell.createChooser.single")
                    .disabled(isStarting || !canStartSingleSession)

                    Button {
                        let chosen = effectiveVehicleId
                        dismiss()
                        onConvoy(chosen)
                    } label: {
                        SocialFlow {
                            Image(systemName: "person.3.fill").font(.system(size: 24)).accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                                Text("shell.createChooserConvoy")
                                    .font(.headline)
                                Text("shell.createChooserConvoyBody")
                                    .font(.subheadline)
                            }
                            SocialFlowSpacer()
                        }
                        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isStarting)
                }
                .padding(KccSpacing.s4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle("shell.createChooserTitle")
            .navigationDestination(isPresented: $showingAllVehicles) {
                ScrollView {
                    VStack(spacing: KccSpacing.s1) {
                        ForEach(vehicles) { vehicle in
                            vehicleButton(vehicle) {
                                selectedVehicleId = vehicle.id
                                showingAllVehicles = false
                            }
                        }
                    }
                    .padding(KccSpacing.s4)
                }
                .kccReadingSurface(pageBackground: true)
                .navigationTitle("shell.createChooserAllVehicles")
                .navigationBarTitleDisplayMode(.inline)
            }
        }
        .presentationDetents([.medium, .large], selection: $detent)
        .kccDismissibleSheet()
        .presentationBackground(.regularMaterial)
        .task { garage.start() }
        .task(id: needsRanking) {
            guard needsRanking, rankedVehicleIds == nil else { return }
            let ids = await garage.rankedDrivingVehicleIds()
            if !Task.isCancelled, rankedVehicleIds == nil {
                rankedVehicleIds = ids
            }
        }
        .task(id: needsRanking) {
            guard needsRanking, rankedVehicleIds == nil else { return }
            do {
                try await Task.sleep(for: .milliseconds(1_500))
                // Freeze the fallback order for this presentation when offline
                // or the endpoint is too slow. A late response cannot move rows.
                if rankedVehicleIds == nil { rankedVehicleIds = [] }
            } catch {
                return
            }
        }
        .onChange(of: vehicles.count, initial: true) { _, count in
            if count >= 3 { detent = .large }
        }
        .onChange(of: garage.state, initial: true) { _, _ in
            selectedVehicleId = StartDrivingSelection.effectiveVehicleId(
                selected: selectedVehicleId,
                vehicles: vehicles
            )
        }
    }

    @ViewBuilder
    private var vehiclePicker: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("shell.createChooserCarLabel")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(KccTheme.secondary)

            switch garage.state {
            case .loading:
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 72)
            case .failed:
                VStack(alignment: .leading, spacing: KccSpacing.s2) {
                    Text("garage.error")
                        .foregroundStyle(KccPalette.errorRed)
                    Button("garage.retryButton") { garage.reload() }
                }
            case .unavailable:
                Text("garage.error")
                    .font(.subheadline)
                    .foregroundStyle(KccTheme.secondary)
            case .empty:
                Text("shell.createChooserNoCars")
                    .font(.subheadline)
                    .foregroundStyle(KccTheme.secondary)
            case .loaded:
                let visible = needsRanking && rankedVehicleIds == nil
                    ? []
                    : StartDrivingSelection.quickVehicles(vehicles, rankedIds: rankedVehicleIds ?? [])
                if let selected = vehicles.first(where: { $0.id == effectiveVehicleId }),
                   !visible.contains(where: { $0.id == selected.id }) {
                    Text(String(format: String(localized: "shell.createChooserSelectedVehicle"), vehicleLabel(selected)))
                        .font(.subheadline)
                }
                ForEach(visible) { vehicle in
                    vehicleButton(vehicle) { selectedVehicleId = vehicle.id }
                }
                if vehicles.count > 3 {
                    Button("shell.createChooserAllVehicles") {
                        detent = .large
                        showingAllVehicles = true
                    }
                    .frame(minHeight: 44)
                }
            }
        }
    }

    private func vehicleLabel(_ vehicle: Vehicle) -> String {
        VehicleDisplay.headline(vehicle, otherLabel: String(localized: "garage.catalogueOther"))
    }

    private func vehicleButton(_ vehicle: Vehicle, onSelect: @escaping () -> Void) -> some View {
        let selected = effectiveVehicleId == vehicle.id
        return Button(action: onSelect) {
            HStack(spacing: KccSpacing.s3) {
                ZStack {
                    Color(.secondarySystemBackground)
                    if let path = vehicle.imagePath, let url = garage.imageURLs[path] {
                        AsyncImage(url: url) { image in
                            image.resizable().scaledToFill()
                        } placeholder: {
                            vehiclePlaceholder
                        }
                    } else {
                        vehiclePlaceholder
                    }
                }
                .frame(width: 32, height: 32)
                .clipShape(.rect(cornerRadius: 6))
                .accessibilityHidden(true)

                Text(verbatim: vehicleLabel(vehicle))
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(KccTheme.driver)
                    .opacity(selected ? 1 : 0)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 48)
            .padding(.horizontal, KccSpacing.s2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private var vehiclePlaceholder: some View {
        Image(systemName: "car.fill")
            .foregroundStyle(KccTheme.secondary)
    }

    private var needsRanking: Bool { vehicles.count > 1 }

    private var vehicles: [Vehicle] {
        if case .loaded(let vehicles) = garage.state { return vehicles }
        return []
    }

    private var effectiveVehicleId: String? {
        StartDrivingSelection.effectiveVehicleId(
            selected: selectedVehicleId,
            vehicles: vehicles
        )
    }
}
