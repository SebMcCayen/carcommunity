import SwiftUI

/// The Friends surface — the iOS port of Android's `FriendsScreen`:
/// add-by-nickname (with an ambiguity picker), incoming and outgoing pending
/// requests, and the established friends list (client-side sort, Crown
/// Points chip, message / profile / remove per row). Every backend error is
/// surfaced via a `friends.*` string keyed off the mapped
/// ``FriendActionError`` — never a raw message.
///
/// Exported ready for the shell-wiring PR (``ShellRoute/friends``): the
/// route host will wrap it in a `NavigationStack` and supply
/// `onMessageFriend` / `onViewProfile`; until then both default to nil and
/// the corresponding affordances are hidden.
struct FriendsScreen: View {
    /// Nil in a config-less build; the screen degrades to a placeholder.
    let coordinator: FriendsCoordinator?
    /// Opens the 1:1 DM thread with the friend (the conversation is created
    /// on the first message). Nil until the shell wires it.
    var onMessageFriend: ((FriendSummary) -> Void)?
    /// Opens the friend's read-only member profile. Nil until the shell
    /// wires it.
    var onViewProfile: ((FriendSummary) -> Void)?

    @State private var nickname = ""
    @State private var removeTarget: FriendSummary?
    /// Client-side ordering of the established list. Defaults to
    /// earliest-added, which is the order `friend-list` already returns, so
    /// the list never reorders on first load.
    @State private var friendSort: FriendSort = .earliestAdded

    var body: some View {
        content
            .navigationTitle(Text("shell.friendsTitle"))
            .task { await coordinator?.load() }
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            loaded(coordinator)
        } else {
            VStack(spacing: KccSpacing.s2) {
                Text("shell.friendsTitle")
                    .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.semibold))
                Text("common.placeholder")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    @ViewBuilder
    private func loaded(_ coordinator: FriendsCoordinator) -> some View {
        List {
            addFriendSection(coordinator)
            switch coordinator.status {
            case .loading:
                Section {
                    HStack(spacing: KccSpacing.s3) {
                        ProgressView()
                        Text("friends.listTitle")
                            .foregroundStyle(.secondary)
                    }
                }
            case .error(let error):
                Section {
                    VStack(alignment: .leading, spacing: KccSpacing.s3) {
                        Text(FriendsScreenStrings.statusErrorKey(error))
                        Button {
                            Task { await coordinator.load() }
                        } label: {
                            Text("friends.retry")
                        }
                        .buttonStyle(.bordered)
                    }
                }
            case .loaded(let friends, let incoming, let outgoing, let points):
                if let actionError = coordinator.actionError {
                    Section {
                        actionErrorBanner(actionError, coordinator: coordinator)
                    }
                }
                if !incoming.isEmpty {
                    incomingSection(incoming, coordinator: coordinator)
                }
                if !outgoing.isEmpty {
                    outgoingSection(outgoing, coordinator: coordinator)
                }
                friendsSection(friends, points: points, coordinator: coordinator)
            }
        }
        .listStyle(.insetGrouped)
        .confirmationDialog(
            Text("friends.removeConfirmTitle"),
            isPresented: Binding(
                get: { removeTarget != nil },
                set: { if !$0 { removeTarget = nil } }
            ),
            titleVisibility: .visible,
            presenting: removeTarget
        ) { friend in
            Button(role: .destructive) {
                Task { await coordinator.remove(friendUid: friend.uid) }
            } label: {
                Text("friends.removeConfirmAction")
            }
            Button(role: .cancel) {} label: {
                Text("friends.removeCancel")
            }
        } message: { _ in
            Text("friends.removeConfirmBody")
        }
    }

    // MARK: - Add friend

    @ViewBuilder
    private func addFriendSection(_ coordinator: FriendsCoordinator) -> some View {
        Section {
            HStack(spacing: KccSpacing.s2) {
                TextField(text: $nickname) {
                    Text("friends.nicknameLabel")
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onChange(of: nickname) {
                    // Clear a stale result/error as soon as the caller edits
                    // the field.
                    switch coordinator.add {
                    case .idle, .working: break
                    default: coordinator.resetAdd()
                    }
                }
                Button {
                    let value = nickname
                    Task { await coordinator.sendRequest(nickname: value) }
                } label: {
                    if coordinator.add == .working {
                        Text("friends.addWorking")
                    } else {
                        Text("friends.addAction")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(
                    coordinator.add == .working
                        || nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                )
            }
            switch coordinator.add {
            case .sent(let nowFriends):
                Text(nowFriends ? "friends.nowFriends" : "friends.requestSent")
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(KccPalette.successGreen)
            case .error(let error):
                Text(FriendsScreenStrings.addErrorKey(error))
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(KccPalette.errorRed)
            case .chooser(let candidates):
                // The nickname matched several members — an inline picker
                // (Android renders a dialog; a list section keeps this dumb).
                VStack(alignment: .leading, spacing: KccSpacing.s2) {
                    Text("friends.chooseMember")
                        .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
                    Text("friends.chooseMemberBody")
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(.secondary)
                    ForEach(candidates, id: \.uid) { candidate in
                        Button {
                            Task { await coordinator.chooseCandidate(uid: candidate.uid) }
                        } label: {
                            Text(candidate.displayName ?? String(localized: "friends.unknownMember"))
                        }
                    }
                    Button(role: .cancel) {
                        coordinator.resetAdd()
                    } label: {
                        Text("friends.chooseCancel")
                    }
                }
            case .idle, .working:
                EmptyView()
            }
        } header: {
            Text("friends.addSectionTitle")
        }
    }

    // MARK: - Requests

    private func incomingSection(
        _ incoming: [FriendRequestSummary],
        coordinator: FriendsCoordinator
    ) -> some View {
        Section {
            ForEach(incoming) { request in
                let busy = coordinator.busyRows.contains(
                    FriendsCoordinator.respondBusyKey(request.requestId)
                )
                HStack(spacing: KccSpacing.s2) {
                    memberLabel(request.otherUser.displayName)
                    Spacer()
                    Button {
                        Task { await coordinator.accept(requestId: request.requestId) }
                    } label: {
                        Text("friends.accept")
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy)
                    Button(role: .destructive) {
                        Task { await coordinator.decline(requestId: request.requestId) }
                    } label: {
                        Text("friends.decline")
                    }
                    .buttonStyle(.bordered)
                    .disabled(busy)
                }
                // Row-scoped buttons: without this, a List row treats every
                // button tap as a whole-row tap.
                .buttonStyle(.borderless)
            }
        } header: {
            Text("friends.incomingTitle")
        }
    }

    private func outgoingSection(
        _ outgoing: [FriendRequestSummary],
        coordinator: FriendsCoordinator
    ) -> some View {
        Section {
            ForEach(outgoing) { request in
                let busy = coordinator.busyRows.contains(
                    FriendsCoordinator.cancelBusyKey(request.toUid)
                )
                HStack(spacing: KccSpacing.s2) {
                    VStack(alignment: .leading, spacing: KccSpacing.s1) {
                        memberLabel(request.otherUser.displayName)
                        Text("friends.outgoingPendingLabel")
                            .font(.system(size: KccTypeScale.caption))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(role: .destructive) {
                        Task { await coordinator.cancel(toUid: request.toUid) }
                    } label: {
                        Text("friends.cancelRequestAction")
                    }
                    .buttonStyle(.bordered)
                    .disabled(busy)
                }
                .buttonStyle(.borderless)
            }
        } header: {
            Text("friends.outgoingTitle")
        }
    }

    // MARK: - Friends list

    @ViewBuilder
    private func friendsSection(
        _ friends: [FriendSummary],
        points: [String: Int64],
        coordinator: FriendsCoordinator
    ) -> some View {
        Section {
            if friends.isEmpty {
                Text("friends.emptyFriends")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(sortFriends(friends, by: friendSort)) { friend in
                    friendRow(friend, points: points[friend.uid] ?? 0, coordinator: coordinator)
                }
            }
        } header: {
            HStack {
                Text("friends.listTitle")
                Spacer()
                if friends.count > 1 {
                    sortMenu
                }
            }
        }
    }

    /// The sort control: a compact menu over the three client-side orderings
    /// (Android's FilterChip row).
    private var sortMenu: some View {
        Menu {
            Picker(selection: $friendSort) {
                Text("friends.sortEarliestAdded").tag(FriendSort.earliestAdded)
                Text("friends.sortRecentlyAdded").tag(FriendSort.recentlyAdded)
                Text("friends.sortAlphabetical").tag(FriendSort.name)
            } label: {
                Text("friends.sortLabel")
            }
        } label: {
            Label {
                Text("friends.sortLabel")
            } icon: {
                Image(systemName: "arrow.up.arrow.down")
            }
            .font(.system(size: KccTypeScale.caption))
        }
    }

    private func friendRow(
        _ friend: FriendSummary,
        points: Int64,
        coordinator: FriendsCoordinator
    ) -> some View {
        let busy = coordinator.busyRows.contains(FriendsCoordinator.removeBusyKey(friend.uid))
        return HStack(spacing: KccSpacing.s3) {
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                memberLabel(friend.displayName)
                pointsChip(points)
            }
            Spacer()
            if let onMessageFriend {
                Button {
                    onMessageFriend(friend)
                } label: {
                    Text("friends.message")
                }
                .buttonStyle(.bordered)
            }
            if let onViewProfile {
                Button {
                    onViewProfile(friend)
                } label: {
                    Image(systemName: "person.crop.circle")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text(friend.displayName ?? ""))
            }
        }
        .buttonStyle(.borderless)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) {
                removeTarget = friend
            } label: {
                Text("friends.remove")
            }
            .disabled(busy)
        }
    }

    /// Compact "1 240 Kronpoäng" chip beside a friend's name; a friend absent
    /// from the balances map renders as 0 (Android: `FriendPointsChip`).
    private func pointsChip(_ points: Int64) -> some View {
        HStack(spacing: KccSpacing.s1) {
            Text(verbatim: FriendPointsFormat.grouped(points))
                .font(.system(size: KccTypeScale.caption, weight: KccTypeScale.semibold))
            Text("profile.pointsTitle")
                .font(.system(size: KccTypeScale.caption))
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, KccSpacing.s2)
        .padding(.vertical, KccSpacing.s1 / 2)
        .background(KccPalette.crownGold.opacity(0.2))
        .clipShape(Capsule())
    }

    private func memberLabel(_ displayName: String?) -> Text {
        if let displayName, !displayName.isEmpty {
            Text(verbatim: displayName)
        } else {
            Text("friends.unknownMember")
        }
    }

    private func actionErrorBanner(
        _ error: FriendActionError,
        coordinator: FriendsCoordinator
    ) -> some View {
        HStack(spacing: KccSpacing.s2) {
            Text(FriendsScreenStrings.actionErrorKey(error))
                .font(.system(size: KccTypeScale.bodySm))
                .foregroundStyle(KccPalette.errorRed)
            Spacer()
            Button {
                coordinator.clearActionError()
            } label: {
                Text("friends.close")
            }
            .buttonStyle(.borderless)
        }
    }
}

/// The `friends.*` key for each mapped error — the same per-category strings
/// Android renders. Pure so the mapping is unit-testable.
enum FriendsScreenStrings {
    static func addErrorKey(_ error: FriendActionError) -> LocalizedStringKey {
        key(for: error)
    }

    static func actionErrorKey(_ error: FriendActionError) -> LocalizedStringKey {
        key(for: error)
    }

    static func statusErrorKey(_ error: FriendActionError) -> LocalizedStringKey {
        switch error {
        // The list load renders its dedicated load-error string for the
        // generic case (Android: friends_loadError), and the specific
        // category otherwise.
        case .generic: return "friends.loadError"
        default: return key(for: error)
        }
    }

    private static func key(for error: FriendActionError) -> LocalizedStringKey {
        switch error {
        case .signedOut: return "friends.errorSignedOut"
        case .notMember: return "friends.errorNotMember"
        case .invalid: return "friends.errorInvalid"
        case .selfRequest: return "friends.errorSelfRequest"
        case .notFound: return "friends.errorNotFound"
        case .alreadyFriends: return "friends.errorAlreadyFriends"
        case .requestAlreadySent: return "friends.errorRequestAlreadySent"
        case .notAddable: return "friends.errorNotAddable"
        case .requestGone: return "friends.errorRequestGone"
        case .network: return "friends.errorNetwork"
        case .temporarilyUnavailable: return "friends.errorTemporarilyUnavailable"
        case .generic: return "friends.errorGeneric"
        }
    }
}

// MARK: - Previews

#Preview("Config-less") {
    NavigationStack {
        FriendsScreen(coordinator: nil)
    }
}

#Preview("Loaded") {
    NavigationStack {
        FriendsScreen(
            coordinator: FriendsCoordinator(
                repository: PreviewFriendsRepository(
                    result: .loaded(
                        FriendsData(
                            friends: [
                                FriendSummary(
                                    uid: "a",
                                    displayName: "GT86_swe",
                                    avatarPath: nil,
                                    friendsSince: "2026-01-15T10:00:00.000Z"
                                ),
                                FriendSummary(
                                    uid: "b",
                                    displayName: "Åsa",
                                    avatarPath: nil,
                                    friendsSince: "2026-03-02T10:00:00.000Z"
                                ),
                            ],
                            incoming: [
                                FriendRequestSummary(
                                    requestId: "r1",
                                    fromUid: "c",
                                    toUid: "me",
                                    direction: .incoming,
                                    otherUser: FriendUser(
                                        uid: "c", displayName: "Volvo-Erik", avatarPath: nil
                                    ),
                                    createdAt: nil
                                )
                            ],
                            outgoing: [
                                FriendRequestSummary(
                                    requestId: "r2",
                                    fromUid: "me",
                                    toUid: "d",
                                    direction: .outgoing,
                                    otherUser: FriendUser(
                                        uid: "d", displayName: "Saab-Sara", avatarPath: nil
                                    ),
                                    createdAt: nil
                                )
                            ]
                        )
                    )
                )
            ),
            onMessageFriend: { _ in },
            onViewProfile: { _ in }
        )
    }
}

/// Scripted ``FriendsRepository`` for previews: `list()` returns the fixed
/// result; every mutation succeeds inertly.
final class PreviewFriendsRepository: FriendsRepository, @unchecked Sendable {
    private let result: FriendsResult

    init(result: FriendsResult) {
        self.result = result
    }

    func list() async -> FriendsResult { result }
    func sendRequest(nickname: String) async -> SendRequestResult { .requested }
    func sendRequest(toUid: String) async -> SendRequestResult { .requested }
    func respond(requestId: String, accept: Bool) async -> RespondResult {
        accept ? .accepted : .declined
    }
    func cancelRequest(toUid: String) async -> CancelResult { .cancelled }
    func remove(friendUid: String) async -> RemoveResult { .removed }
}
