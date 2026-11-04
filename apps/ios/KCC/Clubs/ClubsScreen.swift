import SwiftUI

/// Native Social → Klubben, backed by the same canonical Driver callables as
/// Android. Public club pages never expose a roster, private calendar or chat.
struct ClubsScreen: View {
    let coordinator: ClubsCoordinator?
    var initialClubID: String? = nil
    var onInitialClubConsumed: () -> Void = {}
    @State private var showsCreate = false
    @State private var confirmsLeave = false
    @State private var showsSentInvitations = false
    @State private var showsInvite = false

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
        .task(id: coordinator?.enabled) {
            guard let coordinator, coordinator.enabled else { return }
            await coordinator.reload()
            if let initialClubID {
                await coordinator.open(id: initialClubID)
                onInitialClubConsumed()
            }
        }
        .refreshable { await coordinator?.reload() }
        .sheet(isPresented: $showsCreate) {
            if let coordinator { ClubCreateSheet(coordinator: coordinator) }
        }
        .sheet(isPresented: $showsSentInvitations, onDismiss: { coordinator?.clearSentInvitations() }) {
            if let coordinator { ClubSentInvitationsSheet(coordinator: coordinator) }
        }
        .sheet(isPresented: $showsInvite) {
            if let coordinator { ClubInviteFriendsSheet(coordinator: coordinator) }
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
            Button("clubs.retry") { Task { await coordinator.retry() } }.frame(minHeight: 48)
        }
        if coordinator.loading { ProgressView("clubs.loading") }
        Picker("clubs.title", selection: Binding(
            get: { coordinator.selectedTab }, set: { coordinator.selectTab($0) }
        )) {
            Text("clubs.myClub").tag(ClubTab.myClub)
            Text("clubs.allClubs").tag(ClubTab.allClubs)
        }
        .pickerStyle(.segmented)
        .accessibilityIdentifier("clubs.tabs")
        if coordinator.selectedTab == .myClub {
            if let state = coordinator.state {
                if let club = state.club {
                    ClubPresentation(club: club)
                    clubStatistics(club)
                    if coordinator.canManageInvitations {
                        Text("clubs.members").font(.headline).accessibilityAddTraits(.isHeader)
                        ViewThatFits(in: .horizontal) {
                            HStack { invitationButtons(coordinator, club: club) }
                            VStack(alignment: .leading) { invitationButtons(coordinator, club: club) }
                        }
                    }
                    Button("clubs.leave", role: .destructive) { confirmsLeave = true }
                        .disabled(coordinator.working || (state.role == "owner" && club.memberCount > 1)).frame(minHeight: 48)
                    if state.role == "owner" && club.memberCount > 1 {
                        Text("clubs.ownershipError").font(.footnote).foregroundStyle(KccTheme.secondary)
                    }
                } else {
                    Text("clubs.noMembership")
                    if state.legacyConflict { Text("clubs.conflict").foregroundStyle(KccTheme.error) }
                    if let until = state.joinAllowedAt, until > Date() {
                        Text("clubs.cooldown".localizedFormat(until.formatted(date: .abbreviated, time: .shortened)))
                            .font(.callout)
                    }
                    if state.canJoin(at: Date()) {
                        Button("clubs.create") { showsCreate = true }.kccPrimaryAction().frame(minHeight: 48)
                    }
                    Button("clubs.allClubs") { coordinator.selectTab(.allClubs) }.frame(minHeight: 48)
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
        } else if coordinator.openingClub {
            ProgressView("clubs.loading")
        } else if let club = coordinator.selectedClub {
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
            Text("clubs.directory").font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
            if !coordinator.loading && !coordinator.failed && coordinator.otherClubs.isEmpty && coordinator.nextCursor == nil {
                Text("clubs.empty")
            }
            ForEach(coordinator.otherClubs) { club in
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
    @ViewBuilder private func invitationButtons(_ coordinator: ClubsCoordinator, club: CommunityClub) -> some View {
        if club.visibility == "closed" {
            Button("clubs.invite") { showsInvite = true }.frame(minHeight: 48).disabled(coordinator.working)
        }
        Button("clubs.pendingInvitations") { showsSentInvitations = true }
            .frame(minHeight: 48).disabled(coordinator.working)
            .accessibilityIdentifier("clubs.pendingInvitations")
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


struct ClubSentInvitationsSheet: View {
    let coordinator: ClubsCoordinator
    @Environment(\.dismiss) private var dismiss
    @State private var confirmation: SentClubInvitation?

    var body: some View {
        NavigationStack {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                List {
                    if !coordinator.canManageInvitations {
                        Text("clubs.readOnly")
                    } else {
                        if coordinator.loadingInvitations || coordinator.working { ProgressView("clubs.loading") }
                        if coordinator.invitationsFailed {
                            Text("clubs.error").foregroundStyle(KccTheme.error)
                            Button("clubs.retry") { Task { await coordinator.loadSentInvitations() } }
                                .frame(minHeight: 48).disabled(coordinator.working || coordinator.loadingInvitations)
                        }
                        let pending = coordinator.sentInvitations.filter { $0.expiresAt > context.date }
                        if pending.isEmpty && !coordinator.loadingInvitations && !coordinator.invitationsFailed {
                            Text("clubs.noPendingInvitations")
                        }
                        ForEach(pending) { invitation in
                            VStack(alignment: .leading, spacing: KccSpacing.s2) {
                                Text(verbatim: invitation.displayName.isEmpty ? NSLocalizedString("clubs.member", comment: "") : invitation.displayName).font(.headline)
                                Text(String(format: NSLocalizedString("clubs.invitedAt", comment: ""),
                                            invitation.sentAt.formatted(date: .abbreviated, time: .shortened)))
                                Button("clubs.revokeInvitation", role: .destructive) { confirmation = invitation }
                                    .frame(minHeight: 48).disabled(coordinator.working || coordinator.loadingInvitations)
                            }
                        }
                        if coordinator.invitationsCursor != nil {
                            Button("clubs.more") { Task { await coordinator.loadSentInvitations(append: true) } }
                                .frame(minHeight: 48).disabled(coordinator.loadingInvitations || coordinator.working)
                        }
                    }
                }
            }
            .navigationTitle("clubs.pendingInvitations")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("clubs.close") { dismiss() }.disabled(coordinator.working)
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("clubs.refreshInvitations") { Task { await refresh() } }
                        .disabled(coordinator.working || coordinator.loadingInvitations)
                }
            }
            .refreshable { await refresh() }
            .task { await refresh() }
            .confirmationDialog("clubs.revokeInvitation", isPresented: Binding(
                get: { confirmation != nil }, set: { if !$0 { confirmation = nil } }), titleVisibility: .visible) {
                if let invitation = confirmation {
                    Button("clubs.revokeInvitation", role: .destructive) {
                        Task { await coordinator.revoke(invitation); confirmation = nil }
                    }.disabled(coordinator.working || !coordinator.canManageInvitations)
                }
                Button("clubs.cancel", role: .cancel) { confirmation = nil }
            } message: {
                if let invitation = confirmation {
                    Text(String(format: NSLocalizedString("clubs.revokeInvitationConfirm", comment: ""),
                                invitation.displayName.isEmpty ? NSLocalizedString("clubs.member", comment: "") : invitation.displayName))
                }
            }
        }
        .interactiveDismissDisabled(coordinator.working)
    }
    private func refresh() async {
        await coordinator.reload()
        await coordinator.loadSentInvitations()
    }
}

/// Uses the same existing friend picker and canonical invite action as Android.
struct ClubInviteFriendsSheet: View {
    let coordinator: ClubsCoordinator
    @Environment(\.dismiss) private var dismiss
    @State private var friends: [FriendSummary] = []
    @State private var search = ""
    @State private var loading = true
    @State private var failed = false
    var body: some View {
        NavigationStack {
            List {
                if loading { ProgressView("clubs.loading") }
                if failed || coordinator.failed {
                    Text("clubs.error").foregroundStyle(KccTheme.error)
                    Button("clubs.retry") { Task { await load() } }.frame(minHeight: 48)
                }
                if !loading && !failed && friends.isEmpty { Text("clubs.noFriends") }
                ForEach(friends.filter { search.isEmpty || ($0.displayName ?? "").localizedCaseInsensitiveContains(search) }) { friend in
                    Button {
                        guard coordinator.canManageInvitations, let club = coordinator.state?.club, club.visibility == "closed" else { return }
                        Task { if await coordinator.manage("invite", fields: ["clubId": club.id, "targetUid": friend.uid]) { dismiss() } }
                    } label: { Text(verbatim: friend.displayName ?? NSLocalizedString("clubs.member", comment: "")) }
                        .frame(minHeight: 48).disabled(coordinator.working || !coordinator.canManageInvitations)
                }
            }
            .searchable(text: $search, prompt: "clubs.search")
            .navigationTitle("clubs.inviteFriends")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("clubs.close") { dismiss() }.disabled(coordinator.working) } }
            .task { await load() }
        }.interactiveDismissDisabled(coordinator.working)
    }
    private func load() async {
        loading = true; failed = false; friends = []
        if let result = await coordinator.friendsRepository?.list(), case .loaded(let data) = result, !Task.isCancelled {
            friends = data.friends.sorted { ($0.displayName ?? "").localizedCompare($1.displayName ?? "") == .orderedAscending }
        } else { failed = true }
        loading = false
    }
}
