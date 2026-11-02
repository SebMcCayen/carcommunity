import SwiftUI

/// Native Social → Klubben, backed by the same canonical Driver callables as
/// Android. Public club pages never expose a roster, private calendar or chat.
struct ClubsScreen: View {
    let coordinator: ClubsCoordinator?
    @State private var showsCreate = false
    @State private var confirmsLeave = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s4) {
                if let coordinator, coordinator.enabled {
                    clubContent(coordinator)
                } else {
                    SocialReadingState(title: "clubs.title", message: "clubs.unavailable", symbol: "person.3")
                }
            }
            .padding(KccSpacing.s4)
        }
        .kccReadingSurface(pageBackground: true)
        .navigationTitle("clubs.title")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("clubs.screen")
        .task { await coordinator?.reload() }
        .refreshable { await coordinator?.reload() }
        .sheet(isPresented: $showsCreate) {
            if let coordinator { ClubCreateSheet(coordinator: coordinator) }
        }
        .confirmationDialog("clubs.leaveConfirm", isPresented: $confirmsLeave, titleVisibility: .visible) {
            if let club = coordinator?.state?.club {
                Button("clubs.confirm", role: .destructive) {
                    Task { await coordinator?.manage("leave", fields: ["clubId": club.id]) }
                }
            }
            Button("clubs.cancel", role: .cancel) {}
        }
    }

    @ViewBuilder private func clubContent(_ coordinator: ClubsCoordinator) -> some View {
        if coordinator.failed {
            Text("clubs.error").foregroundStyle(KccTheme.error)
            Button("clubs.retry") { Task { await coordinator.reload() } }.frame(minHeight: 48)
        }
        if coordinator.loading { ProgressView("clubs.loading") }
        if let club = coordinator.selectedClub {
            Button { coordinator.closeDetail() } label: { Label("clubs.back", systemImage: "chevron.backward") }
                .frame(minHeight: 48)
            ClubPresentation(club: club)
            clubStatistics(club)
            if let state = coordinator.state, state.canJoin(at: Date()), club.visibility == "open" {
                Button("clubs.join") { Task { await coordinator.manage("join", fields: ["clubId": club.id]) } }
                    .kccPrimaryAction().disabled(coordinator.working)
                    .frame(minHeight: 48)
            }
            Text("clubs.readOnly").font(.footnote).foregroundStyle(KccTheme.secondary)
        } else {
            if let state = coordinator.state {
                if let club = state.club {
                    ClubPresentation(club: club)
                    clubStatistics(club)
                    Button("clubs.leave", role: .destructive) { confirmsLeave = true }
                        .disabled(coordinator.working || (state.role == "owner" && club.memberCount > 1)).frame(minHeight: 48)
                    if state.role == "owner" && club.memberCount > 1 {
                        Text("clubs.ownershipError").font(.footnote).foregroundStyle(KccTheme.secondary)
                    }
                } else {
                    if state.legacyConflict { Text("clubs.conflict").foregroundStyle(KccTheme.error) }
                    if let until = state.joinAllowedAt, until > Date() {
                        Text("clubs.cooldown".localizedFormat(until.formatted(date: .abbreviated, time: .shortened)))
                            .font(.callout)
                    }
                    if state.canJoin(at: Date()) {
                        Button("clubs.create") { showsCreate = true }.kccPrimaryAction().frame(minHeight: 48)
                    }
                }
                if !state.invitations.isEmpty {
                    Text("clubs.invitations").font(.headline)
                    ForEach(state.invitations.filter { $0.expiresAt > Date() }) { invite in
                        VStack(alignment: .leading, spacing: KccSpacing.s2) {
                            Text(verbatim: invite.clubName).font(.headline)
                            Button("clubs.accept") {
                                Task { await coordinator.manage("acceptInvitation", fields: ["clubId": invite.clubId]) }
                            }.disabled(!state.canJoin(at: Date()) || coordinator.working).frame(minHeight: 48)
                        }.kccContentCard()
                    }
                }
            }
            Text("clubs.directory").font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
            if !coordinator.loading && !coordinator.failed && coordinator.clubs.isEmpty { Text("clubs.empty") }
            ForEach(coordinator.clubs) { club in
                Button { Task { await coordinator.open(club) } } label: { ClubPresentation(club: club) }
                    .buttonStyle(.plain)
                    .accessibilityHint(Text("clubs.title"))
            }
            if coordinator.nextCursor != nil {
                Button("clubs.more") { Task { await coordinator.reload(append: true) } }
                    .disabled(coordinator.loading).frame(minHeight: 48)
            }
        }
    }
    private func clubStatistics(_ club: CommunityClub) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text("clubs.statistics").font(.headline)
            LabeledContent("clubs.drives", value: club.driveCount.formatted())
            LabeledContent("clubs.distance", value: (club.distanceMeters / 1000).formatted(.number.precision(.fractionLength(1))))
            LabeledContent("clubs.duration", value: (club.durationSeconds / 3600).formatted(.number.precision(.fractionLength(1))))
            Text("clubs.statsNote").font(.footnote).foregroundStyle(KccTheme.secondary)
        }.kccContentCard()
    }
}

private struct ClubPresentation: View {
    let club: CommunityClub
    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            HStack(alignment: .top, spacing: KccSpacing.s3) {
                AsyncImage(url: club.logoURL) { image in image.resizable().scaledToFill() } placeholder: {
                    Text(verbatim: String(club.name.prefix(2)).uppercased()).font(.headline)
                        .frame(maxWidth: .infinity, maxHeight: .infinity).background(KccTheme.subtle)
                }
                .frame(width: 56, height: 56).clipShape(Circle())
                .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: KccSpacing.s1) {
                    Text(verbatim: club.name).font(.headline)
                    Text(verbatim: club.location).font(.subheadline).foregroundStyle(KccTheme.secondary)
                }
            }
            Text(verbatim: club.description).fixedSize(horizontal: false, vertical: true)
            Text("clubs.membersCount".localizedFormat(club.memberCount)).font(.subheadline)
            Text(club.visibility == "closed" ? LocalizedStringKey("clubs.closed") : LocalizedStringKey("clubs.open"))
                .font(.footnote).foregroundStyle(KccTheme.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).kccContentCard()
    }
}

private struct ClubCreateSheet: View {
    let coordinator: ClubsCoordinator
    @Environment(\.dismiss) private var dismiss
    @State private var form = ClubForm()
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("clubs.name", text: $form.name)
                    TextField("clubs.description", text: $form.description, axis: .vertical).lineLimit(3...6)
                    TextField("clubs.location", text: $form.location)
                    Toggle("clubs.closed", isOn: $form.closed)
                    Text("clubs.invalidForm").font(.footnote).foregroundStyle(KccTheme.secondary)
                }
                if coordinator.failed { Text("clubs.error").foregroundStyle(KccTheme.error) }
            }
            .kccReadingSurface(pageBackground: true)
            .navigationTitle("clubs.create")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("clubs.cancel") { dismiss() }.disabled(coordinator.working)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("clubs.save") {
                        Task { if await coordinator.manage("create", fields: form.fields) { dismiss() } }
                    }.disabled(!form.isValid || coordinator.working || coordinator.state?.canJoin(at: Date()) != true)
                }
            }
        }
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(coordinator.working)
    }
}

private extension String {
    func localizedFormat(_ value: String) -> String { String(format: NSLocalizedString(self, comment: ""), value) }
    func localizedFormat(_ value: Int) -> String { String(format: NSLocalizedString(self, comment: ""), value) }
}
