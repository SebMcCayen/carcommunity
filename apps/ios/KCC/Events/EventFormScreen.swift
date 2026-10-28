import CoreLocation
import MapboxMaps
import SwiftUI

struct EventFormScreen: View {
    @Environment(\.dismiss) private var dismiss
    @State var coordinator: EventFormCoordinator
    let initial: EventFormInput
    let locationProvider: any LocationProvider

    @State private var title: String
    @State private var startsAt: Date
    @State private var description: String
    @State private var address: String
    @State private var latitude: Double?
    @State private var longitude: Double?
    @State private var publicSiteEnabled: Bool
    @State private var showLocationPicker = false
    @State private var showValidation = false

    init(
        coordinator: EventFormCoordinator,
        initial: EventFormInput? = nil,
        locationProvider: any LocationProvider
    ) {
        let fallback = initial ?? EventFormInput(
            title: "",
            startsAt: Date().addingTimeInterval(60 * 60),
            description: nil,
            address: nil,
            latitude: nil,
            longitude: nil,
            publicSiteEnabled: false
        )
        _coordinator = State(initialValue: coordinator)
        self.initial = fallback
        self.locationProvider = locationProvider
        _title = State(initialValue: fallback.title)
        _startsAt = State(initialValue: fallback.startsAt)
        _description = State(initialValue: fallback.description ?? "")
        _address = State(initialValue: fallback.address ?? "")
        _latitude = State(initialValue: fallback.latitude)
        _longitude = State(initialValue: fallback.longitude)
        _publicSiteEnabled = State(initialValue: fallback.publicSiteEnabled)
    }

    private var isEdit: Bool {
        if case .edit = coordinator.mode { return true }
        return false
    }

    private var saving: Bool { coordinator.state == .saving }

    var body: some View {
        NavigationStack {
            Form {
                if !isEdit {
                    Section {
                        Text("events.createLiveNotice")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Section {
                    EditorTextField(title: "events.createFieldTitle", text: $title)
                        .textInputAutocapitalization(.sentences)
                    DatePicker("events.createPickStart", selection: $startsAt, displayedComponents: [.date, .hourAndMinute])
                        .frame(minHeight: 48)
                    EditorTextField(title: "events.createFieldDescription", text: $description, multiline: true)
                    EditorTextField(title: "events.createFieldAddress", text: $address)
                }

                Section {
                    EventLocationSummary(latitude: latitude, longitude: longitude)
                    Button { showLocationPicker = true } label: {
                        Text(latitude == nil ? "events.createLocationPick" : "events.createLocationEdit").frame(minHeight: 48)
                    }.buttonStyle(.bordered)
                    if latitude != nil {
                        Button(role: .destructive) { latitude = nil; longitude = nil } label: {
                            Text("events.createLocationClear").frame(minHeight: 48)
                        }.buttonStyle(.bordered).tint(KccPalette.errorRed)
                    }
                }

                if !isEdit {
                    Section {
                        Toggle("events.createPublicSiteLabel", isOn: $publicSiteEnabled).frame(minHeight: 48)
                        Text("events.createPublicSiteHelp")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                if showValidation {
                    Text("events.createValidation")
                        .foregroundStyle(KccPalette.errorRed)
                }
                if let failureKey {
                    Text(failureKey)
                        .foregroundStyle(KccPalette.errorRed)
                }
            }
            .disabled(saving)
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Button(action: submit) {
                    EditorSubmitLabel(title: isEdit ? (saving ? "events.editSaving" : "events.editSubmit")
                        : (saving ? "events.createSaving" : "events.createSubmit"), saving: saving)
                }.kccPrimaryAction().disabled(saving)
                    .padding(KccSpacing.s4).background(KccTheme.surface)
            }
            .navigationTitle(Text(isEdit ? "events.editTitle" : "events.createTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text("events.createCancel").frame(minHeight: 48) }.disabled(saving)
                }
            }
            .sheet(isPresented: $showLocationPicker) {
                EventLocationPickerScreen(
                    initialLatitude: latitude,
                    initialLongitude: longitude,
                    locationProvider: locationProvider
                ) { latitude, longitude in
                    self.latitude = latitude
                    self.longitude = longitude
                }
            }
            .onChange(of: coordinator.state) { _, state in
                switch state {
                case .created, .updated: dismiss()
                default: break
                }
            }
        }.interactiveDismissDisabled(saving)
    }

    private var failureKey: LocalizedStringKey? {
        switch coordinator.state {
        case .failedCreate(.rateLimited): "events.createRateLimited"
        case .failedCreate: "events.createError"
        case .failedEdit(.permissionDenied): "events.editErrorPermission"
        case .failedEdit(.immutable): "events.editErrorImmutable"
        case .failedEdit: "events.editError"
        default: nil
        }
    }

    private func submit() {
        let input = EventFormInput(
            title: title,
            startsAt: startsAt,
            description: description,
            address: address,
            latitude: latitude,
            longitude: longitude,
            publicSiteEnabled: isEdit ? initial.publicSiteEnabled : publicSiteEnabled
        )
        showValidation = !Events.valid(input)
        guard !showValidation else { return }
        coordinator.submit(input)
    }
}

/// Full-screen centre-pin picker. The map starts at the existing pin, then a
/// fresh authorized device fix, then Kungsbacka. Moving the map moves the pin;
/// only Confirm commits it to the form.
private struct EventLocationPickerScreen: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    let locationProvider: any LocationProvider
    let onConfirm: (Double, Double) -> Void
    private let accessToken: String?

    @State private var viewport: Viewport
    @State private var selectedLatitude: Double
    @State private var selectedLongitude: Double
    @State private var seededFromDevice = false

    init(
        initialLatitude: Double?,
        initialLongitude: Double?,
        locationProvider: any LocationProvider,
        onConfirm: @escaping (Double, Double) -> Void
    ) {
        let latitude = initialLatitude ?? 57.4872
        let longitude = initialLongitude ?? 12.0761
        self.locationProvider = locationProvider
        self.onConfirm = onConfirm
        let token = MapboxConfiguration.accessToken()
        self.accessToken = token
        if let token { MapboxOptions.accessToken = token }
        _selectedLatitude = State(initialValue: latitude)
        _selectedLongitude = State(initialValue: longitude)
        _viewport = State(initialValue: .camera(
            center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
            zoom: 14,
            bearing: 0,
            pitch: 0
        ))
        _seededFromDevice = State(initialValue: initialLatitude != nil && initialLongitude != nil)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                if accessToken != nil {
                    // `accessToken` being present is the config-safe renderer gate;
                    // MapboxOptions was set during initialization.
                    MapboxMaps.Map(viewport: $viewport)
                        .mapStyle(.standard)
                        .onCameraChanged { context in
                            selectedLatitude = context.cameraState.center.latitude
                            selectedLongitude = context.cameraState.center.longitude
                        }
                } else {
                    KccTheme.subtle
                    Text("events.locationPickerUnavailable")
                        .foregroundStyle(KccTheme.secondary)
                }
                Image(systemName: "mappin.circle.fill")
                    .font(.system(size: 36))
                    .foregroundStyle(KccPalette.crownGold)
                    .accessibilityLabel(Text("events.locationPickerPin"))
                    .allowsHitTesting(false)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(spacing: KccSpacing.s3) {
                    ScrollView {
                        Text("events.locationPickerHint").font(.footnote).foregroundStyle(KccTheme.secondary)
                            .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                    }.frame(height: typeSize.isAccessibilitySize ? 120 : 64)
                    Button {
                        onConfirm(selectedLatitude, selectedLongitude)
                        dismiss()
                    } label: { EditorSubmitLabel(title: "events.locationPickerConfirm") }
                        .kccPrimaryAction().disabled(accessToken == nil)
                }.padding(KccSpacing.s4).background(KccTheme.surface)
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle(Text("events.createLocationPick"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text("events.locationPickerCancel").frame(minHeight: 48) }
                }
            }
            .task { await seedFromCurrentLocationIfAvailable() }
        }
    }

    @MainActor
    private func seedFromCurrentLocationIfAvailable() async {
        guard !seededFromDevice, locationProvider.authorization.isAuthorized else { return }
        seededFromDevice = true
        let stream = locationProvider.fixes()
        await withTaskGroup(of: LocationFix?.self) { group in
            group.addTask {
                for await fix in stream { return fix }
                return nil
            }
            group.addTask {
                try? await Task.sleep(for: .seconds(3))
                return nil
            }
            if let fix = await group.next() ?? nil {
                selectedLatitude = fix.latitude
                selectedLongitude = fix.longitude
                viewport = .camera(
                    center: CLLocationCoordinate2D(latitude: fix.latitude, longitude: fix.longitude),
                    zoom: 14,
                    bearing: 0,
                    pitch: 0
                )
            }
            group.cancelAll()
        }
    }
}
