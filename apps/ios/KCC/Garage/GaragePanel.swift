import Foundation
import PhotosUI
import SwiftUI

/// Panel CONTENT for the Garage tab — what the tab shows is the user's own
/// cars and the add affordance directly, with no hub screen in between
/// (Android's `GarageScreen` posture). Rendered inside the shell's
/// `TranslucentShellPanel` like the other panel tabs.
///
/// Includes the complete owner-management flow: detail, edit/delete,
/// gallery management, and main-car selection.
struct GaragePanel: View {
    @State private var coordinator: GarageCoordinator
    @State private var isAddPresented = false
    @State private var selectedVehicleId: String?

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
                    .font(.title2.weight(.semibold))

                content
            }
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .foregroundStyle(KccTheme.text)
        .background(KccTheme.page)
        .accessibilityIdentifier("garage.screen")
        .task { coordinator.start() }
        .sheet(isPresented: $isAddPresented) {
            VehicleFormSheet(coordinator: coordinator, vehicle: nil)
        }
        .sheet(
            isPresented: Binding(
                get: { selectedVehicleId != nil },
                set: { if !$0 { selectedVehicleId = nil } }
            )
        ) {
            if let id = selectedVehicleId, let vehicle = coordinator.vehicle(id: id) {
                VehicleDetailSheet(vehicle: vehicle, coordinator: coordinator)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch coordinator.state {
        case .loading:
            Text("garage.loading")
                .font(.body)
                .foregroundStyle(.secondary)
        case .unavailable:
            // The config-less build. There is no dedicated "unavailable" key
            // in the garage contract strings (Android has no such state), so
            // the generic load-error copy is the closest honest message —
            // the same reuse posture as EventsScreen's placeholder.
            Text("garage.error")
                .font(.body)
                .foregroundStyle(.secondary)
        case .failed:
            Text("garage.error")
                .font(.body)
                .foregroundStyle(KccPalette.errorRed)
            Button(action: { coordinator.reload() }) {
                Text("garage.retryButton")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity, minHeight: 48)
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
                .font(.body)
                .foregroundStyle(.secondary)
        } else {
            ForEach(vehicles) { vehicle in
                Button { selectedVehicleId = vehicle.id } label: {
                    GarageVehicleCard(
                        vehicle: vehicle,
                        imageURL: vehicle.imagePath.flatMap { coordinator.imageURLs[$0] }
                    )
                }
                .buttonStyle(.plain)
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
        .font(.subheadline)
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
            .font(.body)
            .foregroundStyle(.secondary)
        }
    }

    private var addButton: some View {
        Button {
            coordinator.resetSaveStatus()
            isAddPresented = true
        } label: {
            Text("garage.addVehicle")
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .kccPrimaryAction()
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

/// Photo-led card using the same resolved cover and owner actions as before.
/// The Android catalogue's main-car emphasis never changes vehicle allowance.
struct GarageVehicleCard: View {
    let vehicle: Vehicle
    let imageURL: URL?

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            photo
            Text(VehicleDisplay.headline(vehicle, otherLabel: String(localized: "garage.catalogueOther")))
                .font(.title3.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
            Text(LocalizedStringKey(vehicle.powertrain.localizationKey))
                .font(.subheadline)
                .foregroundStyle(KccTheme.secondary)
            if let engine = vehicle.engineDescription, !engine.isEmpty {
                Text(engine)
                    .font(.subheadline)
                    .foregroundStyle(KccTheme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if vehicle.isMainCar {
                Text("garage.mainCarBadge")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, KccSpacing.s3)
                    .padding(.vertical, KccSpacing.s1)
                    .foregroundStyle(KccTheme.onDriver)
                    .background(KccTheme.driver, in: Capsule())
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard(border: vehicle.isMainCar ? KccTheme.driver : KccTheme.border,
                        lineWidth: vehicle.isMainCar ? 2 : 1)
        .accessibilityIdentifier("garage.vehicle.\(vehicle.id)")
    }

    private var photo: some View {
        Color.clear
            .aspectRatio(16.0 / 9.0, contentMode: .fit)
            .overlay {
                GeometryReader { geometry in
                    ZStack {
                        KccTheme.subtle
                        if let imageURL {
                            AsyncImage(url: imageURL) { phase in
                                if case .success(let image) = phase {
                                    image.resizable().scaledToFill()
                                        .frame(width: geometry.size.width, height: geometry.size.height)
                                } else { photoPlaceholder }
                            }
                        } else { photoPlaceholder }
                    }
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .clipped()
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("garage.photoAlt"))
    }

    private var photoPlaceholder: some View {
        Image(systemName: "car.fill")
            .font(.largeTitle)
            .foregroundStyle(KccTheme.secondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityHidden(true)
    }
}

/// Self-contained owner detail page. Every mutation is reflected by the live
/// garage listener; the sheet receives the latest vehicle value whenever that
/// snapshot changes.
struct VehicleDetailSheet: View {
    let vehicle: Vehicle
    @Bindable var coordinator: GarageCoordinator

    @Environment(\.dismiss) private var dismiss
    @State private var isEditing = false
    @State private var deleteConfirmation = false
    @State private var removalPath: String?
    @State private var pickedPhoto: PhotosPickerItem?
    @State private var selectedPhotoIndex = 0
    @State private var photoProcessingFailed = false

    private var paths: [String] { VehicleGallery.paths(for: vehicle) }
    private var isWorking: Bool { coordinator.mutationStatus == .working }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    gallery

                    Text(
                        VehicleDisplay.headline(
                            vehicle, otherLabel: String(localized: "garage.catalogueOther")
                        )
                    )
                    .font(.title2.bold())

                    if vehicle.isMainCar {
                        Text("garage.mainCarBadge")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(KccTheme.driver)
                    }

                    if let plate = vehicle.registrationPlate, !plate.isEmpty {
                        infoRow("garage.registrationPlate", value: plate)
                    }
                    infoRow(
                        "garage.powertrain",
                        value: String(localized: String.LocalizationValue(vehicle.powertrain.localizationKey))
                    )
                    if let engine = vehicle.engineDescription, !engine.isEmpty {
                        infoRow("garage.engineDescription", value: engine)
                    }
                    if let modifications = vehicle.modifications, !modifications.isEmpty {
                        infoRow("garage.modifications", value: modifications)
                    }

                    PhotosPicker(selection: $pickedPhoto, matching: .images) {
                        Text("garage.photoAddMore")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .disabled(paths.count >= VehicleValidation.maxVehiclePhotos || isWorking)

                    if paths.count >= VehicleValidation.maxVehiclePhotos {
                        Text("garage.photoAddMoreUnavailable")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    if isWorking {
                        ProgressView("garage.photoUploading")
                    }
                    if photoProcessingFailed {
                        Text("garage.photoTooLarge")
                            .font(.subheadline)
                            .foregroundStyle(KccPalette.errorRed)
                    } else if coordinator.mutationStatus == .failed {
                        Text("garage.errorDetail")
                            .font(.subheadline)
                            .foregroundStyle(KccPalette.errorRed)
                    }

                    Button {
                        Task {
                            await coordinator.setMainVehicle(vehicle.id, isMain: !vehicle.isMainCar)
                        }
                    } label: {
                        Text(vehicle.isMainCar ? "garage.unsetMainCar" : "garage.setMainCar")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isWorking)

                    SocialFlow {
                        Button {
                            coordinator.resetSaveStatus()
                            isEditing = true
                        } label: { Text("garage.editVehicle").frame(maxWidth: .infinity, minHeight: 48) }
                            .buttonStyle(.bordered)
                        Button(role: .destructive) { deleteConfirmation = true } label: {
                            Text("garage.deleteVehicle").frame(maxWidth: .infinity, minHeight: 48)
                        }.buttonStyle(.bordered).tint(KccPalette.errorRed)
                    }
                    .disabled(isWorking)
                }
                .padding(KccSpacing.s4)
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle(Text("garage.detailTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text("garage.cancelButton").frame(minHeight: 48) }
                }
            }
        }
        .sheet(isPresented: $isEditing) {
            VehicleFormSheet(coordinator: coordinator, vehicle: vehicle)
        }
        .alert("garage.deleteVehicle", isPresented: $deleteConfirmation) {
            Button("garage.cancelButton", role: .cancel) {}
            Button("garage.deleteConfirmButton", role: .destructive) {
                Task {
                    if await coordinator.deleteVehicle(vehicle.id) { dismiss() }
                }
            }
        } message: {
            Text("garage.deleteConfirm")
        }
        .alert(
            "garage.photoRemove",
            isPresented: Binding(
                get: { removalPath != nil },
                set: { if !$0 { removalPath = nil } }
            )
        ) {
            Button("garage.cancelButton", role: .cancel) { removalPath = nil }
            Button("garage.photoRemoveConfirmButton", role: .destructive) {
                guard let path = removalPath else { return }
                removalPath = nil
                Task { await coordinator.removePhoto(vehicleId: vehicle.id, photoPath: path) }
            }
        } message: {
            Text("garage.photoRemoveConfirm")
        }
        .onChange(of: pickedPhoto) { _, item in
            guard let item else { return }
            Task { await addPhoto(item) }
        }
        .onChange(of: paths.count) { _, count in
            selectedPhotoIndex = VehicleGallery.clampedIndex(selectedPhotoIndex, count: count)
        }
    }

    @ViewBuilder
    private var gallery: some View {
        if paths.isEmpty {
            ZStack {
                Circle().fill(KccTheme.subtle)
                Image(systemName: "car").font(.system(size: 48)).foregroundStyle(.secondary)
            }
            .aspectRatio(1, contentMode: .fit)
            .accessibilityLabel(Text("garage.photoNone"))
        } else {
            TabView(selection: $selectedPhotoIndex) {
                ForEach(Array(paths.enumerated()), id: \.element) { index, path in
                    galleryImage(path: path).tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: paths.count > 1 ? .automatic : .never))
            .aspectRatio(1, contentMode: .fit)
            .clipShape(Circle())

            let current = VehicleGallery.clampedIndex(selectedPhotoIndex, count: paths.count)
            let currentPath = paths[current]
            if paths.count > 1 {
                Text(
                    String.localizedStringWithFormat(
                        String(localized: "garage.photoCounter"), current + 1, paths.count
                    )
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
            if current == 0 {
                Text("garage.photoCoverBadge")
                    .font(.footnote.weight(.semibold))
            }
            SocialFlow {
                Button {
                    Task {
                        await coordinator.setCover(vehicleId: vehicle.id, photoPath: currentPath, currentPaths: paths)
                    }
                } label: { Text("garage.photoSetCover").frame(maxWidth: .infinity, minHeight: 48) }
                    .disabled(current == 0 || isWorking).buttonStyle(.bordered)
                Button(role: .destructive) { removalPath = currentPath } label: {
                    Text("garage.photoRemove").frame(maxWidth: .infinity, minHeight: 48)
                }.disabled(isWorking).buttonStyle(.bordered).tint(KccPalette.errorRed)
            }
        }
    }

    private func galleryImage(path: String) -> some View {
        ZStack {
            KccTheme.subtle
            if let url = coordinator.imageURLs[path] {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    ProgressView()
                }
            } else {
                Image(systemName: "car").font(.system(size: 48)).foregroundStyle(.secondary)
            }
        }
        .clipShape(Circle())
        .accessibilityLabel(Text("garage.photoAlt"))
    }

    private func infoRow(_ label: LocalizedStringKey, value: String) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            Text(label)
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text(verbatim: value).font(.body)
        }
    }

    private func addPhoto(_ item: PhotosPickerItem) async {
        pickedPhoto = nil
        photoProcessingFailed = false
        coordinator.resetMutationStatus()
        guard paths.count < VehicleValidation.maxVehiclePhotos,
            let raw = try? await item.loadTransferable(type: Data.self),
            let jpeg = AvatarImageProcessor.jpegData(from: raw)
        else {
            photoProcessingFailed = true
            return
        }
        _ = await coordinator.addPhoto(vehicleId: vehicle.id, jpegData: jpeg)
    }
}

/// Which selector sheet is open. Only one at a time, so a single value beats
/// three booleans that could disagree (Android's `VehiclePicker`).
private enum VehiclePickerSheet: String, Identifiable {
    case make, model, year
    var id: String { rawValue }
}

/// The add/edit vehicle form — the iOS port of Android's `VehicleFormScreen`.
/// Owns its field state; validates against the
/// backend bounds (``VehicleValidation``) before reporting a payload, and
/// closes on a successful save.
///
/// Make, model and year are SELECTED from ``VehicleCatalogue`` through three
/// dependent selectors (choosing a manufacturer filters the models); there is
/// no free-text field for any of them, because per-manufacturer counts only
/// work if everyone's Volvo stores the same id.
struct VehicleFormSheet: View {
    @Bindable var coordinator: GarageCoordinator
    let vehicle: Vehicle?
    @Environment(\.dismiss) private var dismiss

    @State private var form: VehicleForm
    @State private var openPicker: VehiclePickerSheet?
    /// Validation runs live but is only SHOWN after a save attempt, so a
    /// half-filled form is not shouting errors while the member types
    /// (Android's `showError`).
    @State private var showValidation = false

    init(coordinator: GarageCoordinator, vehicle: Vehicle?) {
        self.coordinator = coordinator
        self.vehicle = vehicle
        _form = State(initialValue: vehicle.map(VehicleForm.init(vehicle:)) ?? VehicleForm())
    }

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
                            .font(.subheadline)
                            .foregroundStyle(KccPalette.errorRed)
                    }
                }

                if coordinator.saveStatus == .failed {
                    Section {
                        Text("garage.saveError")
                            .font(.subheadline)
                            .foregroundStyle(KccPalette.errorRed)
                    }
                }

            }
            .disabled(coordinator.saveStatus == .saving)
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Button(action: save) {
                    EditorSubmitLabel(title: "garage.saveVehicle", saving: coordinator.saveStatus == .saving)
                }.kccPrimaryAction().disabled(coordinator.saveStatus == .saving)
                    .padding(KccSpacing.s4).background(KccTheme.surface)
            }
            .navigationTitle(Text(vehicle == nil ? "garage.formTitleCreate" : "garage.formTitleEdit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: { dismiss() }) {
                        Text("garage.cancelButton").frame(minHeight: 48)
                    }.disabled(coordinator.saveStatus == .saving)
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
        }.interactiveDismissDisabled(coordinator.saveStatus == .saving)
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
        Section { VehiclePowertrainPicker(selection: $form.powertrain) }
    }

    private var detailsSection: some View {
        Section {
            EditorTextField(title: "garage.engineDescription", text: $form.engineDescription)
            EditorTextField(title: "garage.modifications", text: $form.modifications, multiline: true)
            EditorTextField(title: "garage.registrationPlate", text: $form.registrationPlate)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
        } footer: {
            // The plate is DELIBERATELY PUBLIC (readable by any signed-in
            // user) — the hint copy says so; entering one is opting in.
            Text("garage.registrationPlateHint")
                .font(.footnote)
        }
    }

    private func selectorRow(
        label: LocalizedStringKey,
        value: Text?,
        prompt: LocalizedStringKey,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            EditorSelectionLabel(title: label, value: value, prompt: prompt)
        }.buttonStyle(.plain)
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
            await coordinator.saveVehicle(input, editingVehicleId: vehicle?.id)
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
                        .font(.subheadline)
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
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: { dismiss() }) {
                        Text("garage.pickerClose").frame(minHeight: 48)
                    }
                }
            }
        }
    }

    private func rows(_ options: [CatalogueOption]) -> some View {
        ForEach(options) { option in
            Button(action: { onSelect(option) }) { CatalogueOptionLabel(option: option) }
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
                    Text(verbatim: String(year)).font(.body).frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle(Text("garage.selectModelYear"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: { dismiss() }) {
                        Text("garage.pickerClose").frame(minHeight: 48)
                    }
                }
            }
        }
    }
}

#Preview("Unavailable (config-less build)") {
    GaragePanel(coordinator: GarageCoordinator(repository: nil, uid: nil))
}
