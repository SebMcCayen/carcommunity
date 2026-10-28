import SwiftUI

struct AddressSearchOverlay: View {
    @Bindable var coordinator: SavedPlacesCoordinator
    let proximity: MapPoint?
    let onSelect: (PlaceSuggestion) -> Void
    let onManage: () -> Void
    let onClose: () -> Void
    @State private var editor: PlaceEditorContext?
    @FocusState private var searchFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: KccSpacing.s2) {
                Button(action: close) {
                    Label("addressSearch.close", systemImage: "xmark")
                        .labelStyle(.iconOnly)
                        .frame(width: 48, height: 48)
                }
                .accessibilityIdentifier("addressSearch.close")
                TextField(
                    "addressSearch.searchPlaceholder",
                    text: Binding(
                        get: { coordinator.query },
                        set: { coordinator.updateQuery($0, proximity: proximity) }
                    )
                )
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .accessibilityIdentifier("addressSearch.query")
                .focused($searchFocused)
                if !coordinator.query.isEmpty {
                    Button {
                        coordinator.clearSearch()
                    } label: {
                        Label("addressSearch.clearQuery", systemImage: "xmark.circle.fill")
                            .labelStyle(.iconOnly)
                            .frame(width: 48, height: 48)
                    }
                }
            }
            .padding(KccSpacing.s3)

            Divider()
            results
        }
        .frame(maxWidth: 620, maxHeight: 560, alignment: .top)
        .background(KccTheme.overlay, in: RoundedRectangle(cornerRadius: KccRadius.lg))
        .overlay { RoundedRectangle(cornerRadius: KccRadius.lg).strokeBorder(KccTheme.border) }
        .padding(.horizontal, KccSpacing.s3)
        .padding(.trailing, KccSpacing.s12 + KccSpacing.s4)
        .padding(.top, KccSpacing.s8)
        .frame(maxHeight: .infinity, alignment: .top)
        .task { searchFocused = true }
        .accessibilityAction(.escape, close)
        .sheet(item: $editor) { context in
            SavedPlaceEditorSheet(
                place: context.place,
                initialKind: context.kind,
                initialLabel: context.label,
                onSave: { kind, label in
                    coordinator.save(kind: kind, place: context.place, label: label)
                    editor = nil
                },
                onCancel: { editor = nil }
            )
            .presentationDetents([.medium, .large])
        }
        .savedPlacesPersistenceAlert(coordinator: coordinator)
    }

    @ViewBuilder
    private var results: some View {
        let requestQuery = SavedPlacesPolicy.normalizedQuery(coordinator.query)
        if requestQuery.count < 2 {
            List {
                if !coordinator.places.isEmpty {
                    Section("addressSearch.savedTitle") {
                        ForEach(coordinator.places) { saved in
                            Button { onSelect(saved.place) } label: {
                                PlaceRow(
                                    title: saved.displayLabel,
                                    subtitle: saved.place.secondaryText,
                                    systemImage: saved.kind.systemImage
                                )
                            }
                        }
                    }
                }
                Section {
                    Button(action: onManage) {
                        Label("settingsMenu.savedPlaces", systemImage: "bookmark")
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        } else if coordinator.isSearching {
            ProgressView("addressSearch.loading")
                .frame(maxWidth: .infinity, minHeight: 150)
        } else if coordinator.searchFailed {
            ContentUnavailableView(
                "addressSearch.searchError",
                systemImage: "wifi.exclamationmark"
            )
            .frame(minHeight: 180)
        } else if coordinator.suggestions.isEmpty {
            ContentUnavailableView.search(text: requestQuery)
                .frame(minHeight: 180)
        } else {
            List(coordinator.suggestions) { suggestion in
                HStack(spacing: KccSpacing.s2) {
                    Button { onSelect(suggestion) } label: {
                        PlaceRow(
                            title: suggestion.name,
                            subtitle: suggestion.secondaryText,
                            systemImage: "mappin.and.ellipse"
                        )
                    }
                    .buttonStyle(.plain)
                    Spacer(minLength: KccSpacing.s2)
                    Button {
                        let existing = SavedPlacesPolicy.editorSeed(
                            for: suggestion,
                            replacing: nil,
                            in: coordinator.places
                        )
                        editor = PlaceEditorContext(
                            place: suggestion,
                            kind: existing?.kind ?? .favourite,
                            label: existing?.label ?? suggestion.name
                        )
                    } label: {
                        Label("addressSearch.savedAdd", systemImage: "bookmark")
                            .labelStyle(.iconOnly)
                            .frame(width: 48, height: 48)
                    }
                }
                // Keep both controls scoped to their own hit areas inside the
                // List row; the primary button's .plain style still wins.
                .buttonStyle(.borderless)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private func close() {
        searchFocused = false
        coordinator.clearSearch()
        onClose()
    }
}

struct SavedPlacesScreen: View {
    @Bindable var coordinator: SavedPlacesCoordinator
    let proximity: MapPoint?
    @State private var picker: SavedPlace?
    @State private var adding = false
    @State private var renaming: SavedPlace?
    @State private var renameLabel = ""
    @State private var deleting: SavedPlace?

    var body: some View {
        List {
            Section {
                Text("savedPlaces.intro")
                    .foregroundStyle(KccTheme.secondary)
                Text("savedPlaces.deviceLocalNote")
                    .font(.footnote)
                    .foregroundStyle(KccTheme.secondary)
            }

            if coordinator.places.isEmpty {
                ContentUnavailableView(
                    "savedPlaces.emptyTitle",
                    systemImage: "bookmark",
                    description: Text("savedPlaces.emptyBody")
                )
                Button { adding = true } label: { Text("savedPlaces.emptyAction").frame(minHeight: 48) }.kccPrimaryAction()
            } else {
                Section {
                    ForEach(coordinator.places) { saved in
                        savedRow(saved)
                    }
                }
                Section {
                    Button { adding = true } label: { Text("savedPlaces.addAction").frame(minHeight: 48) }.kccPrimaryAction()
                }
            }
        }
        .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
        .navigationTitle("savedPlaces.title")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $adding) {
            AddressPlacePicker(
                coordinator: coordinator,
                proximity: proximity,
                replacing: nil,
                onDismiss: { adding = false }
            )
        }
        .sheet(item: $picker) { saved in
            AddressPlacePicker(
                coordinator: coordinator,
                proximity: proximity,
                replacing: saved,
                onDismiss: { picker = nil }
            )
        }
        .alert("savedPlaces.renameTitle", isPresented: Binding(
            get: { renaming != nil },
            set: { if !$0 { renaming = nil } }
        )) {
            TextField("savedPlaces.renameLabel", text: $renameLabel)
            Button("savedPlaces.renameSave") {
                if let renaming { coordinator.rename(renaming, label: renameLabel) }
                renaming = nil
            }
            Button("savedPlaces.cancel", role: .cancel) { renaming = nil }
        }
        .confirmationDialog(
            "savedPlaces.deleteTitle",
            isPresented: Binding(
                get: { deleting != nil },
                set: { if !$0 { deleting = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("savedPlaces.deleteConfirm", role: .destructive) {
                if let deleting { coordinator.remove(id: deleting.id) }
                deleting = nil
            }
            Button("savedPlaces.cancel", role: .cancel) { deleting = nil }
        } message: {
            if let deleting {
                Text(String.localizedStringWithFormat(
                    String(localized: "savedPlaces.deleteMessage"),
                    deleting.displayLabel
                ))
            }
        }
        .savedPlacesPersistenceAlert(coordinator: coordinator)
    }

    private func savedRow(_ saved: SavedPlace) -> some View {
        HStack(spacing: KccSpacing.s3) {
            Image(systemName: saved.kind.systemImage)
                .font(.system(size: 20)).foregroundStyle(KccTheme.driver).frame(width: 28).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                Text(verbatim: saved.displayLabel).font(.body).fixedSize(horizontal: false, vertical: true)
                if let address = saved.place.secondaryText {
                    Text(verbatim: address).font(.subheadline).foregroundStyle(KccTheme.secondary).fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer()
            Menu {
                if saved.kind == .favourite {
                    Button("savedPlaces.rename", systemImage: "pencil") {
                        renameLabel = saved.label
                        renaming = saved
                    }
                }
                Button("savedPlaces.changeAddress", systemImage: "mappin.and.ellipse") {
                    picker = saved
                }
                // Keep coordinates inside the app until iOS has Android's
                // private saved-place friend/DM sharing path. A ShareLink here
                // would expose home/work through a different privacy outcome.
                Button("savedPlaces.delete", systemImage: "trash", role: .destructive) {
                    deleting = saved
                }
            } label: {
                Label("savedPlaces.moreActions", systemImage: "ellipsis.circle")
                    .labelStyle(.iconOnly)
                    .frame(width: 48, height: 48)
            }
            .accessibilityLabel(String.localizedStringWithFormat(
                String(localized: "savedPlaces.moreActions"), saved.displayLabel
            ))
        }
    }
}

private extension View {
    func savedPlacesPersistenceAlert(
        coordinator: SavedPlacesCoordinator
    ) -> some View {
        alert("savedPlaces.persistenceErrorTitle", isPresented: Binding(
            get: { coordinator.persistenceFailed },
            set: { if !$0 { coordinator.dismissPersistenceFailure() } }
        )) {
            Button("savedPlaces.retry") { coordinator.retryPersistence() }
            Button("savedPlaces.cancel", role: .cancel) {
                coordinator.dismissPersistenceFailure()
            }
        } message: {
            Text("savedPlaces.persistenceErrorBody")
        }
    }
}

struct SavedPlacesPickerContent: View {
    let places: [SavedPlace]
    let onSelect: (SavedPlace) -> Void
    let onManage: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
                if places.isEmpty {
                ContentUnavailableView(
                    "shell.savedPlacesPickerEmpty",
                    systemImage: "bookmark"
                )
                } else {
                ForEach(places) { saved in
                    Button { onSelect(saved) } label: {
                        PlaceRow(
                            title: saved.displayLabel,
                            subtitle: saved.place.secondaryText,
                            systemImage: saved.kind.systemImage
                        )
                    }
                }
                }
                Button(action: onManage) {
                Label("settingsMenu.savedPlaces", systemImage: "gearshape").frame(minHeight: 48)
                }
        }
        .buttonStyle(.bordered)
        .tint(KccTheme.driver)
        .controlSize(.large)
    }
}

private struct AddressPlacePicker: View {
    @Bindable var coordinator: SavedPlacesCoordinator
    let proximity: MapPoint?
    let replacing: SavedPlace?
    let onDismiss: () -> Void
    @State private var selected: PlaceSuggestion?

    var body: some View {
        NavigationStack {
            List {
                if coordinator.isSearching {
                    ProgressView("addressSearch.loading")
                } else if coordinator.searchFailed {
                    ContentUnavailableView(
                        "addressSearch.searchError",
                        systemImage: "wifi.exclamationmark"
                    )
                } else {
                    ForEach(coordinator.suggestions) { suggestion in
                        Button { selected = suggestion } label: {
                            PlaceRow(
                                title: suggestion.name,
                                subtitle: suggestion.secondaryText,
                                systemImage: "mappin.and.ellipse"
                            )
                        }
                    }
                }
            }
            .searchable(text: Binding(
                get: { coordinator.query },
                set: { coordinator.updateQuery($0, proximity: proximity) }
            ), prompt: "addressSearch.searchPlaceholder")
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle(Text(LocalizedStringKey(
                replacing == nil ? "savedPlaces.addAction" : "savedPlaces.changeAddress"
            )))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: onDismiss) { Text("savedPlaces.cancel").frame(minHeight: 48) }
                }
            }
            .sheet(item: $selected) { suggestion in
                let seed = SavedPlacesPolicy.editorSeed(
                    for: suggestion,
                    replacing: replacing,
                    in: coordinator.places
                )
                SavedPlaceEditorSheet(
                    place: suggestion,
                    initialKind: seed?.kind ?? .favourite,
                    initialLabel: seed?.label ?? suggestion.name,
                    onSave: { kind, label in
                        coordinator.save(
                            kind: kind,
                            place: suggestion,
                            label: label,
                            replacingID: replacing?.id
                        )
                        selected = nil
                        onDismiss()
                    },
                    onCancel: { selected = nil }
                )
            }
        }
        .onDisappear { coordinator.clearSearch() }
    }
}

struct SavedPlaceEditorSheet: View {
    let place: PlaceSuggestion
    let onSave: (SavedPlaceKind, String) -> Void
    let onCancel: () -> Void
    @State private var kind: SavedPlaceKind
    @State private var label: String

    init(
        place: PlaceSuggestion,
        initialKind: SavedPlaceKind,
        initialLabel: String,
        onSave: @escaping (SavedPlaceKind, String) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.place = place
        self.onSave = onSave
        self.onCancel = onCancel
        _kind = State(initialValue: initialKind)
        _label = State(initialValue: initialLabel)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(place.name)
                    if let address = place.secondaryText {
                        Text(address).font(.footnote).foregroundStyle(KccTheme.secondary)
                    }
                }
                Menu {
                    Picker("addressSearch.savedKindLabel", selection: $kind) {
                        ForEach(SavedPlaceKind.allCases, id: \.self) { option in
                            Text(LocalizedStringKey(option.titleKey)).tag(option)
                        }
                    }
                } label: {
                    EditorSelectionLabel(title: "addressSearch.savedKindLabel", value: Text(LocalizedStringKey(kind.titleKey)), prompt: "addressSearch.savedKindLabel")
                }.buttonStyle(.plain)
                EditorTextField(title: "addressSearch.savedLabelHint", text: $label)
                    .disabled(kind != .favourite)
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Button { onSave(kind, label) } label: { EditorSubmitLabel(title: "addressSearch.savedSave") }
                    .kccPrimaryAction()
                    .disabled(kind == .favourite && label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .padding(KccSpacing.s4).background(KccTheme.surface)
            }
            .navigationTitle("addressSearch.savedDialogTitle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: onCancel) { Text("addressSearch.savedCancel").frame(minHeight: 48) }
                }

            }
        }
    }
}

struct PlaceRow: View {
    let title: String
    let subtitle: String?
    let systemImage: String

    var body: some View {
        HStack(spacing: KccSpacing.s3) {
            Image(systemName: systemImage).font(.system(size: 20)).foregroundStyle(KccTheme.driver).frame(width: 28).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                Text(verbatim: title).font(.body).foregroundStyle(KccTheme.text).fixedSize(horizontal: false, vertical: true)
                if let subtitle {
                    Text(verbatim: subtitle).font(.subheadline).foregroundStyle(KccTheme.secondary).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .contentShape(Rectangle())
    }
}

private struct PlaceEditorContext: Identifiable {
    let place: PlaceSuggestion
    let kind: SavedPlaceKind
    let label: String
    var id: String { place.id }
}
