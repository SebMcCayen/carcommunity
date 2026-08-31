import Foundation
import SwiftUI

/// Panel CONTENT for the Garage tab — what the tab shows is the user's own
/// cars and the add affordance directly, with no hub screen in between
/// (Android's `GarageScreen` posture). Rendered inside the shell's
/// `TranslucentShellPanel` like the other panel tabs.
///
/// This slice: the vehicle list plus the add-vehicle flow. The manage
/// actions (edit, delete, photos, main car) arrive with later slices, so the
/// cards are display-only for now.
struct GaragePanel: View {
    @State private var coordinator: GarageCoordinator
    @State private var isAddPresented = false

    /// Production wiring: builds the coordinator from the feature-level
    /// factories (the same construction pattern as ``ProfileScreen``). In a
    /// config-less build both factories return nil and the coordinator
    /// settles on ``GarageUiState/unavailable``.
    init() {
        let uid = Self.signedInUid()
        self.init(
            coordinator: GarageCoordinator(
                repository: FirebaseVehiclesRepository.createIfAvailable(),
                subscriptionRepository: FirebaseSubscriptionStateRepository.createIfAvailable(),
                uid: uid
            )
        )
    }

    /// Preview/test seam: inject a coordinator (typically fed by a fake
    /// repository).
    init(coordinator: GarageCoordinator) {
        _coordinator = State(initialValue: coordinator)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s4) {
                Text("garage.screenTitle")
                    .font(.system(size: KccTypeScale.headingLg, weight: KccTypeScale.semibold))

                content
            }
            .padding(KccSpacing.s6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .task { coordinator.start() }
        .sheet(isPresented: $isAddPresented) {
            AddVehicleSheet(coordinator: coordinator)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch coordinator.state {
        case .loading:
            Text("garage.loading")
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(.secondary)
        case .unavailable:
            // The config-less build. There is no dedicated "unavailable" key
            // in the garage contract strings (Android has no such state), so
            // the generic load-error copy is the closest honest message —
            // the same reuse posture as EventsScreen's placeholder.
            Text("garage.error")
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(.secondary)
        case .failed:
            Text("garage.error")
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(KccPalette.errorRed)
            Button(action: { coordinator.reload() }) {
                Text("garage.retryButton")
                    .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
        case .empty:
            garageContent(vehicles: [])
        case .loaded(let vehicles):
            garageContent(vehicles: vehicles)
        }
    }

    /// Renders every existing car regardless of the current allowance. The
    /// tier gate controls ONLY the Add affordance, so a downgrade never hides
    /// or locks cars the member already owns (and does not interfere with
    /// edit/delete actions as those management slices arrive on iOS).
    @ViewBuilder
    private func garageContent(vehicles: [Vehicle]) -> some View {
        if vehicles.isEmpty {
            Text("garage.empty")
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(.secondary)
        } else {
            ForEach(vehicles) { vehicle in
                GarageVehicleCard(
                    vehicle: vehicle,
                    imageURL: vehicle.imagePath.flatMap { coordinator.imageURLs[$0] }
                )
            }
        }

        let limit = coordinator.vehicleLimit
        Text(
            String.localizedStringWithFormat(
                String(localized: "garage.vehicleAllowance"),
                Int64(vehicles.count),
                Int64(limit)
            )
        )
        .font(.system(size: KccTypeScale.bodySm))
        .foregroundStyle(.secondary)

        if GarageAllowance.canAddVehicle(
            vehicleCount: vehicles.count,
            tier: coordinator.effectiveSubscriptionTier
        ) {
            addButton
        } else {
            Text(
                String.localizedStringWithFormat(
                    String(localized: "garage.vehicleLimitReached"),
                    Int64(limit)
                )
            )
            .font(.system(size: KccTypeScale.bodyMd))
            .foregroundStyle(.secondary)
        }
    }

    private var addButton: some View {
        Button {
            coordinator.resetSaveStatus()
            isAddPresented = true
        } label: {
            Text("garage.addVehicle")
                .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
    }

    /// The signed-in uid from the process-wide auth repository, nil when
    /// Firebase is unconfigured or no session exists — read here (feature
    /// level) so the shell keeps constructing this panel argument-free, the
    /// same seam as ``ProfileScreen``.
    private static func signedInUid() -> String? {
        if case .signedIn(let uid, _)? = FirebaseAuthRepository.createIfAvailable()?.authState {
            return uid
        }
        return nil
    }
}

/// One of the owner's own cars: circular cover photo, the display headline
/// ("Volvo 240 (1988)"), powertrain, engine note, and the main-car badge —
/// the display half of Android's `OwnedVehicleCard`/`VehicleCard`.
struct GarageVehicleCard: View {
    let vehicle: Vehicle
    /// The resolved cover-photo URL; nil keeps the placeholder (a missing
    /// picture is cosmetic, never an error state).
    let imageURL: URL?

    /// Diameter of the circular car photo in the list. Smaller than
    /// Android's 180dp because the iOS card puts the photo beside the text,
    /// not above it — sized like the 96pt profile avatar.
    private static let photoDiameter: CGFloat = 72

    var body: some View {
        HStack(alignment: .center, spacing: KccSpacing.s4) {
            photo

            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                Text(
                    VehicleDisplay.headline(
                        vehicle, otherLabel: String(localized: "garage.catalogueOther")
                    )
                )
                .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.medium))

                Text(LocalizedStringKey(vehicle.powertrain.localizationKey))
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(.secondary)

                if let engine = vehicle.engineDescription, !engine.isEmpty {
                    Text(engine)
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }

                if vehicle.isMainCar {
                    Text("garage.mainCarBadge")
                        .font(.system(size: KccTypeScale.caption, weight: KccTypeScale.semibold))
                        .padding(.horizontal, KccSpacing.s2)
                        .padding(.vertical, KccSpacing.s1)
                        .background(KccPalette.crownGold.opacity(0.25))
                        .clipShape(Capsule())
                }
            }

            Spacer(minLength: 0)
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
    }

    private var photo: some View {
        ZStack {
            Circle()
                .fill(Color(.tertiarySystemBackground))
            if let imageURL {
                AsyncImage(url: imageURL) { image in
                    image
                        .resizable()
                        .scaledToFill()
                } placeholder: {
                    photoPlaceholder
                }
            } else {
                photoPlaceholder
            }
        }
        .frame(width: Self.photoDiameter, height: Self.photoDiameter)
        .clipShape(Circle())
        .accessibilityLabel(Text("garage.photoAlt"))
    }

    private var photoPlaceholder: some View {
        Image(systemName: "car")
            .font(.system(size: KccTypeScale.headingLg))
            .foregroundStyle(.secondary)
    }
}

/// Which selector sheet is open. Only one at a time, so a single value beats
/// three booleans that could disagree (Android's `VehiclePicker`).
private enum VehiclePickerSheet: String, Identifiable {
    case make, model, year
    var id: String { rawValue }
}

/// The add-vehicle form — the iOS port of Android's `VehicleFormScreen`,
/// restricted to the add path. Owns its field state; validates against the
/// backend bounds (``VehicleValidation``) before reporting a payload, and
/// closes on a successful save.
///
/// Make, model and year are SELECTED from ``VehicleCatalogue`` through three
/// dependent selectors (choosing a manufacturer filters the models); there is
/// no free-text field for any of them, because per-manufacturer counts only
/// work if everyone's Volvo stores the same id.
struct AddVehicleSheet: View {
    @Bindable var coordinator: GarageCoordinator
    @Environment(\.dismiss) private var dismiss

    @State private var form = VehicleForm()
    @State private var openPicker: VehiclePickerSheet?
    /// Validation runs live but is only SHOWN after a save attempt, so a
    /// half-filled form is not shouting errors while the member types
    /// (Android's `showError`).
    @State private var showValidation = false

    private var currentYear: Int {
        Calendar.current.component(.year, from: Date())
    }

    private var validationError: VehicleFieldError? {
        VehicleValidation.validate(form, currentYear: currentYear)
    }

    var body: some View {
        NavigationStack {
            Form {
                identitySection
                powertrainSection
                detailsSection

                if showValidation, let error = validationError {
                    Section {
                        Text(LocalizedStringKey(error.localizationKey))
                            .font(.system(size: KccTypeScale.bodySm))
                            .foregroundStyle(KccPalette.errorRed)
                    }
                }

                if coordinator.saveStatus == .failed {
                    Section {
                        Text("garage.saveError")
                            .font(.system(size: KccTypeScale.bodySm))
                            .foregroundStyle(KccPalette.errorRed)
                    }
                }

                Section {
                    Button(action: save) {
                        if coordinator.saveStatus == .saving {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            Text("garage.saveVehicle")
                                .font(
                                    .system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold)
                                )
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .disabled(coordinator.saveStatus == .saving)
                }
            }
            .navigationTitle(Text("garage.formTitleCreate"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: { dismiss() }) {
                        Text("garage.cancelButton")
                    }
                }
            }
            .sheet(item: $openPicker) { picker in
                pickerSheet(for: picker)
            }
            .onChange(of: coordinator.saveStatus) { _, status in
                // Close on success, like Android's LaunchedEffect(saveStatus).
                if status == .saved {
                    dismiss()
                }
            }
        }
    }

    // MARK: - Sections

    private var identitySection: some View {
        Section {
            selectorRow(
                label: "garage.make",
                value: selectedMakeLabel,
                prompt: "garage.selectMake"
            ) { openPicker = .make }

            selectorRow(
                label: "garage.model",
                value: selectedModelLabel,
                prompt: form.makeId == nil ? "garage.selectMakeFirst" : "garage.selectModel"
            ) { openPicker = .model }
                .disabled(form.makeId == nil)

            selectorRow(
                label: "garage.modelYear",
                value: form.modelYear.map { Text(verbatim: String($0)) },
                prompt: "garage.selectModelYear"
            ) { openPicker = .year }
        }
    }

    private var powertrainSection: some View {
        Section {
            // Only the four SELECTABLE powertrains are offered — the retired
            // vocabulary (plug_in_hybrid, other) is parsed and rendered but
            // never offered for a new car.
            Picker(selection: $form.powertrain) {
                ForEach(VehiclePowertrain.selectable, id: \.self) { powertrain in
                    Text(LocalizedStringKey(powertrain.localizationKey))
                        .tag(VehiclePowertrain?.some(powertrain))
                }
            } label: {
                Text("garage.powertrain")
                    .font(.system(size: KccTypeScale.bodyMd))
            }
            .pickerStyle(.menu)
        }
    }

    private var detailsSection: some View {
        Section {
            TextField("garage.engineDescription", text: $form.engineDescription)
            TextField("garage.modifications", text: $form.modifications, axis: .vertical)
                .lineLimit(3...6)
            TextField("garage.registrationPlate", text: $form.registrationPlate)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
        } footer: {
            // The plate is DELIBERATELY PUBLIC (readable by any signed-in
            // user) — the hint copy says so; entering one is opting in.
            Text("garage.registrationPlateHint")
                .font(.system(size: KccTypeScale.caption))
        }
    }

    private func selectorRow(
        label: LocalizedStringKey,
        value: Text?,
        prompt: LocalizedStringKey,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack {
                Text(label)
                    .font(.system(size: KccTypeScale.bodyMd))
                    .foregroundStyle(.primary)
                Spacer()
                if let value {
                    value
                        .font(.system(size: KccTypeScale.bodyMd))
                        .foregroundStyle(.secondary)
                } else {
                    Text(prompt)
                        .font(.system(size: KccTypeScale.bodyMd))
                        .foregroundStyle(.tertiary)
                }
                Image(systemName: "chevron.forward")
                    .font(.system(size: KccTypeScale.caption))
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Selections

    private var selectedMakeLabel: Text? {
        guard let makeId = form.makeId else { return nil }
        if makeId == VehicleCatalogue.otherId { return Text("garage.catalogueOther") }
        return VehicleCatalogue.makeName(makeId).map { Text(verbatim: $0) }
    }

    private var selectedModelLabel: Text? {
        guard let modelId = form.modelId else { return nil }
        if modelId == VehicleCatalogue.otherId { return Text("garage.catalogueOther") }
        return VehicleCatalogue.modelName(makeId: form.makeId, modelId: modelId)
            .map { Text(verbatim: $0) }
    }

    @ViewBuilder
    private func pickerSheet(for picker: VehiclePickerSheet) -> some View {
        switch picker {
        case .make:
            CataloguePickerSheet(
                title: "garage.selectMake",
                options: VehicleCatalogue.makeOptions(),
                groupCommonMakes: true
            ) { option in
                if form.makeId != option.id {
                    form.makeId = option.id
                    // The selectors CASCADE: a model chosen under another
                    // manufacturer is stale, so switching resets it — model
                    // ids are unique only within a manufacturer.
                    form.modelId = nil
                }
                openPicker = nil
            }
        case .model:
            CataloguePickerSheet(
                title: "garage.selectModel",
                options: VehicleCatalogue.modelOptions(makeId: form.makeId),
                groupCommonMakes: false
            ) { option in
                form.modelId = option.id
                openPicker = nil
            }
        case .year:
            YearPickerSheet(years: VehicleCatalogue.modelYears(currentYear: currentYear)) { year in
                form.modelYear = year
                openPicker = nil
            }
        }
    }

    private func save() {
        guard let input = VehicleValidation.toInput(form, currentYear: currentYear) else {
            showValidation = true
            return
        }
        showValidation = false
        Task {
            await coordinator.addVehicle(input)
        }
    }
}

/// Searchable make/model picker — the iOS port of Android's
/// `VehicleCataloguePicker`: a search field, the option list, and the
/// "Other / not listed" escape hatch pinned last and never filtered away.
struct CataloguePickerSheet: View {
    let title: LocalizedStringKey
    let options: [CatalogueOption]
    /// True for the make picker: common (Swedish-roads) brands are surfaced
    /// in their own section above the rest, like Android's grouped list.
    let groupCommonMakes: Bool
    let onSelect: (CatalogueOption) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                let filtered = VehicleCatalogue.filter(options, query: query)
                // Only the pinned Other row surviving a NON-EMPTY query means
                // nothing matched. With no query the hint would be wrong for
                // a picker whose only legitimate row IS the Other bucket
                // (the model picker under an "Other" manufacturer).
                if !query.isEmpty, filtered.allSatisfy(\.isOther) {
                    Text("garage.pickerNoMatches")
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(.secondary)
                }
                if groupCommonMakes, query.isEmpty {
                    Section("garage.commonMakes") {
                        rows(filtered.filter { VehicleCatalogue.make($0.id)?.common == true })
                    }
                    Section("garage.allMakes") {
                        rows(
                            filtered.filter {
                                $0.isOther || VehicleCatalogue.make($0.id)?.common != true
                            }
                        )
                    }
                } else {
                    rows(filtered)
                }
            }
            .searchable(text: $query, prompt: Text("garage.pickerSearch"))
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: { dismiss() }) {
                        Text("garage.pickerClose")
                    }
                }
            }
        }
    }

    private func rows(_ options: [CatalogueOption]) -> some View {
        ForEach(options) { option in
            Button(action: { onSelect(option) }) {
                if option.isOther {
                    VStack(alignment: .leading, spacing: KccSpacing.s1) {
                        Text("garage.catalogueOther")
                            .font(.system(size: KccTypeScale.bodyMd))
                        Text("garage.catalogueOtherHint")
                            .font(.system(size: KccTypeScale.caption))
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Text(verbatim: option.name)
                        .font(.system(size: KccTypeScale.bodyMd))
                }
            }
            .buttonStyle(.plain)
        }
    }
}

/// Year selector, newest first — recent cars are the common case
/// (``VehicleCatalogue/modelYears(currentYear:)``).
struct YearPickerSheet: View {
    let years: [Int]
    let onSelect: (Int) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(years, id: \.self) { year in
                Button(action: { onSelect(year) }) {
                    Text(verbatim: String(year))
                        .font(.system(size: KccTypeScale.bodyMd))
                }
                .buttonStyle(.plain)
            }
            .navigationTitle(Text("garage.selectModelYear"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: { dismiss() }) {
                        Text("garage.pickerClose")
                    }
                }
            }
        }
    }
}

#Preview("Unavailable (config-less build)") {
    GaragePanel(coordinator: GarageCoordinator(repository: nil, uid: nil))
}
