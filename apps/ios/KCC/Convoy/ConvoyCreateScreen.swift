import SwiftUI

struct ConvoyCreateScreen: View {
    @Bindable var coordinator: ConvoyCreateCoordinator
    let friendsCoordinator: FriendsCoordinator?
    let vehicleId: String?
    let onCreated: (ConvoyCreated) -> Void

    @State private var selectedUids = Set<String>()

    var body: some View {
        List {
            availabilitySection

            Section("convoy.pickFriendsTitle") {
                friendRows
            }

            if case .failed(let error) = coordinator.createState {
                Section {
                    Text(ConvoyCreateStrings.errorKey(error))
                        .foregroundStyle(KccPalette.errorRed)
                }
            }

            if selectedUids.count == Self.maxInvitees {
                Section {
                    Text("convoy.createInviteLimit")
                        .foregroundStyle(.secondary)
                }
            }

            Section {
                Button {
                    Task {
                        await coordinator.create(
                            inviteeUids: Array(selectedUids).sorted(),
                            vehicleId: vehicleId
                        )
                    }
                } label: {
                    HStack {
                        Spacer()
                        if isHandingOff {
                            ProgressView()
                        } else {
                            Text("convoy.createSubmit")
                                .font(.system(size: KccTypeScale.bodyMd, weight: .semibold))
                        }
                        Spacer()
                    }
                    .frame(minHeight: 40)
                }
                .disabled(!canSubmit)
            }
        }
        .navigationTitle("convoy.createTitle")
        .task {
            async let convoyLoad: Void = coordinator.load()
            async let friendsLoad: Void? = friendsCoordinator?.load()
            _ = await (convoyLoad, friendsLoad)
        }
        .onChange(of: coordinator.createState) { _, state in
            guard case .created(let created) = state else { return }
            onCreated(created)
        }
    }

    @ViewBuilder
    private var availabilitySection: some View {
        switch coordinator.availability {
        case .loading:
            Section { ProgressView() }
        case .ready(let hasActiveConvoy):
            if hasActiveConvoy {
                Section {
                    Text("convoy.alreadyInConvoyCreateHint")
                        .foregroundStyle(.secondary)
                }
            }
        case .unavailable:
            Section {
                Text("convoy.errorGeneric").foregroundStyle(.secondary)
            }
        case .failed(let error):
            Section {
                VStack(alignment: .leading, spacing: KccSpacing.s2) {
                    Text(ConvoyCreateStrings.errorKey(error))
                        .foregroundStyle(KccPalette.errorRed)
                    Button("convoy.friendsRetry") {
                        Task { await coordinator.load() }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var friendRows: some View {
        if let friendsCoordinator {
            switch friendsCoordinator.status {
            case .loading:
                HStack(spacing: KccSpacing.s3) {
                    ProgressView()
                    Text("friends.listTitle").foregroundStyle(.secondary)
                }
            case .error(let error):
                VStack(alignment: .leading, spacing: KccSpacing.s2) {
                    Text(FriendsScreenStrings.statusErrorKey(error))
                    Button("convoy.friendsRetry") {
                        Task { await friendsCoordinator.load() }
                    }
                }
            case .loaded(let friends, _, _, _):
                if friends.isEmpty {
                    Text("convoy.noFriends").foregroundStyle(.secondary)
                } else {
                    ForEach(friends) { friend in
                        friendButton(friend)
                    }
                }
            }
        } else {
            Text("convoy.errorGeneric").foregroundStyle(.secondary)
        }
    }

    private func friendButton(_ friend: FriendSummary) -> some View {
        let selected = selectedUids.contains(friend.uid)
        return Button {
            if selected {
                selectedUids.remove(friend.uid)
            } else if selectedUids.count < Self.maxInvitees {
                selectedUids.insert(friend.uid)
            }
        } label: {
            HStack(spacing: KccSpacing.s3) {
                Image(systemName: "person.crop.circle")
                    .font(.system(size: 30))
                    .foregroundStyle(.secondary)
                Text(verbatim: friendName(friend))
                Spacer()
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isHandingOff || (!selected && selectedUids.count >= Self.maxInvitees))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private var isHandingOff: Bool {
        switch coordinator.createState {
        case .working, .created: true
        case .idle, .failed: false
        }
    }

    private var canSubmit: Bool {
        guard !selectedUids.isEmpty, !isHandingOff,
              case .ready(let hasActiveConvoy) = coordinator.availability
        else { return false }
        return !hasActiveConvoy
    }

    private func friendName(_ friend: FriendSummary) -> String {
        guard let name = friend.displayName?.trimmingCharacters(in: .whitespacesAndNewlines),
              !name.isEmpty
        else { return String(localized: "friends.unknownMember") }
        return name
    }

    private static let maxInvitees = 50
}
