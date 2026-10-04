import SwiftUI

struct AddressSearchOverlay: View {
    @Bindable var coordinator: SavedPlacesCoordinator
    let proximity: MapPoint?
    let onSelect: (PlaceSuggestion) -> Void
    let onManage: () -> Void
    let onClose: () -> Void
    @State private var editor: PlaceEditorContext?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: KccSpacing.s2) {
                Button(action: close) {
                    Label("addressSearch.close", systemImage: "xmark")
                        .labelStyle(.iconOnly)
                        .frame(width: 44, height: 44)
                }
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
                if !coordinator.query.isEmpty {
                    Button {
                        coordinator.clearSearch()
                    } label: {
                        Label("addressSearch.clearQuery", systemImage: "xmark.circle.fill")
                            .labelStyle(.iconOnly)
                            .frame(width: 44, height: 44)
                    }
                }
            }
            .padding(KccSpacing.s3)

            Divider()
            results
        }
        .frame(maxWidth: 620, maxHeight: 560, alignment: .top)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: KccRadius.lg))
        .padding(.horizontal, KccSpacing.s3)
        .padding(.top, KccSpacing.s8)
        .frame(maxHeight: .infinity, alignment: .top)
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
            .presentationDetents([.medium])
        }
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
                        let existing = SavedPlacesPolicy.existingPlace(
                            matching: suggestion,
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
                            .frame(width: 44, height: 44)
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private func close() {
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
                    .foregroundStyle(.secondary)
                Text("savedPlaces.deviceLocalNote")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if coordinator.places.isEmpty {
                ContentUnavailableView(
                    "savedPlaces.emptyTitle",
                    systemImage: "bookmark",
                    description: Text("savedPlaces.emptyBody")
                )
                Button("savedPlaces.emptyAction") { adding = true }
            } else {
                Section {
                    ForEach(coordinator.places) { saved in
                        savedRow(saved)
                    }
                }
                Section {
                    Button("savedPlaces.addAction") { adding = true }
                }
            }
        }
        .navigationTitle("savedPlaces.title")
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
    }

    private func savedRow(_ saved: SavedPlace) -> some View {
        HStack(spacing: KccSpacing.s3) {
            Image(systemName: saved.kind.systemImage)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                Text(saved.displayLabel)
                if let address = saved.place.secondaryText {
                    Text(address).font(.caption).foregroundStyle(.secondary)
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
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(String.localizedStringWithFormat(
                String(localized: "savedPlaces.moreActions"), saved.displayLabel
            ))
        }
    }
}

struct SavedPlacesPickerSheet: View {
    let places: [SavedPlace]
    let onSelect: (SavedPlace) -> Void
    let onManage: () -> Void

    var body: some View {
        NavigationStack {
            List {
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
                    Label("settingsMenu.savedPlaces", systemImage: "gearshape")
                }
            }
            .navigationTitle("shell.savedPlacesPickerTitle")
        }
        .presentationDetents([.medium, .large])
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
            .navigationTitle(Text(LocalizedStringKey(
                replacing == nil ? "savedPlaces.addAction" : "savedPlaces.changeAddress"
            )))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("savedPlaces.cancel", action: onDismiss)
                }
            }
            .sheet(item: $selected) { suggestion in
                SavedPlaceEditorSheet(
                    place: suggestion,
                    initialKind: replacing?.kind ?? .favourite,
                    initialLabel: replacing?.label ?? suggestion.name,
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

private struct SavedPlaceEditorSheet: View {
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
                        Text(address).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Picker("addressSearch.savedKindLabel", selection: $kind) {
                    ForEach(SavedPlaceKind.allCases, id: \.self) { option in
                        Text(LocalizedStringKey(option.titleKey)).tag(option)
                    }
                }
                TextField("addressSearch.savedLabelHint", text: $label)
                    .disabled(kind != .favourite)
            }
            .navigationTitle("addressSearch.savedDialogTitle")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("addressSearch.savedCancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("addressSearch.savedSave") { onSave(kind, label) }
                        .disabled(
                            kind == .favourite
                                && label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        )
                }
            }
        }
    }
}

private struct PlaceRow: View {
    let title: String
    let subtitle: String?
    let systemImage: String

    var body: some View {
        HStack(spacing: KccSpacing.s3) {
            Image(systemName: systemImage).frame(width: 28)
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                Text(title).foregroundStyle(.primary)
                if let subtitle {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

private struct PlaceEditorContext: Identifiable {
    let place: PlaceSuggestion
    let kind: SavedPlaceKind
    let label: String
    var id: String { place.id }
}
