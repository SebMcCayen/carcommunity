import SwiftUI

/// The iOS Single-session slice of Android's Create chooser. It observes the
/// owner's garage only while presented, preselects the main/first vehicle,
/// and returns the selected id to the shell. Starting with no vehicle remains
/// valid: the backend then renders the generic live marker.
struct StartDrivingSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var garage: GarageCoordinator
    @State private var selectedVehicleId: String?

    let isStarting: Bool
    let onStart: (String?) -> Void

    init(
        garage: GarageCoordinator,
        isStarting: Bool,
        onStart: @escaping (String?) -> Void
    ) {
        _garage = State(initialValue: garage)
        _selectedVehicleId = State(initialValue: nil)
        self.isStarting = isStarting
        self.onStart = onStart
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    Text("shell.createChooserBody")
                        .font(.system(size: KccTypeScale.bodyMd))
                        .foregroundStyle(.secondary)

                    vehiclePicker

                    Button {
                        let chosen = StartDrivingSelection.effectiveVehicleId(
                            selected: selectedVehicleId,
                            vehicles: vehicles
                        )
                        dismiss()
                        onStart(chosen)
                    } label: {
                        HStack(spacing: KccSpacing.s3) {
                            Image(systemName: "car.fill")
                            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                                Text("shell.createChooserSingle")
                                    .font(.system(size: KccTypeScale.titleMd, weight: .semibold))
                                Text("shell.createChooserSingleBody")
                                    .font(.system(size: KccTypeScale.bodySm))
                            }
                            Spacer(minLength: 0)
                        }
                        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isStarting)
                }
                .padding(KccSpacing.s6)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle("shell.createChooserTitle")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("shell.liveSharePromptCancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(.regularMaterial)
        .task { garage.start() }
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
                .font(.system(size: KccTypeScale.bodySm, weight: .semibold))
                .foregroundStyle(.secondary)

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
            case .unavailable, .empty:
                Text("shell.createChooserNoCars")
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(.secondary)
            case .loaded:
                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: KccSpacing.s3) {
                            ForEach(vehicles) { vehicle in
                                vehicleButton(vehicle)
                                    .id(vehicle.id)
                            }
                        }
                    }
                    .onChange(of: selectedVehicleId, initial: true) { _, selected in
                        guard let selected else { return }
                        withAnimation { proxy.scrollTo(selected, anchor: .center) }
                    }
                }
            }
        }
    }

    private func vehicleButton(_ vehicle: Vehicle) -> some View {
        let selected = selectedVehicleId == vehicle.id
        let label = VehicleDisplay.headline(
            vehicle,
            otherLabel: String(localized: "garage.catalogueOther")
        )
        return Button {
            selectedVehicleId = vehicle.id
        } label: {
            ZStack {
                Circle().fill(Color(.secondarySystemBackground))
                if let path = vehicle.imagePath, let url = garage.imageURLs[path] {
                    AsyncImage(url: url) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Image(systemName: "car.fill").foregroundStyle(.secondary)
                    }
                    .clipShape(Circle())
                } else {
                    Image(systemName: "car.fill").foregroundStyle(.secondary)
                }
            }
            .frame(width: 64, height: 64)
            .overlay {
                Circle().stroke(selected ? Color.accentColor : Color.secondary.opacity(0.35), lineWidth: selected ? 3 : 1)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: label))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private var vehicles: [Vehicle] {
        if case .loaded(let vehicles) = garage.state { return vehicles }
        return []
    }
}
