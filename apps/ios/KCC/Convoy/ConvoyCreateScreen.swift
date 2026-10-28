import SwiftUI

struct ConvoyCreateScreen: View {
    @Bindable var coordinator: ConvoyCreateCoordinator
    let friendsCoordinator: FriendsCoordinator?
    let vehicleId: String?
    let onCreated: (ConvoyCreated) -> Void
    @State private var selectedUids = Set<String>()

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                availabilitySection
                Text("convoy.pickFriendsTitle").font(.title3.bold()).accessibilityAddTraits(.isHeader)
                friendRows
                if case .failed(let error) = coordinator.createState {
                    Text(ConvoyCreateStrings.errorKey(error)).foregroundStyle(KccPalette.errorRed).kccContentCard()
                }
                if selectedUids.count == Self.maxInvitees {
                    Text("convoy.createInviteLimit").font(.footnote).foregroundStyle(KccTheme.secondary)
                }
            }.padding(KccSpacing.s4)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            Button {
                Task { await coordinator.create(inviteeUids: Array(selectedUids).sorted(), vehicleId: vehicleId) }
            } label: { ConvoyCommandLabel(title: "convoy.createSubmit", working: isHandingOff) }
                .kccPrimaryAction().disabled(!canSubmit)
                .padding(KccSpacing.s4).background(KccTheme.surface)
        }
        .navigationTitle("convoy.createTitle")
        .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
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
        case .loading: ProgressView().frame(maxWidth: .infinity)
        case .ready(let hasActiveConvoy):
            if hasActiveConvoy { Text("convoy.alreadyInConvoyCreateHint").foregroundStyle(KccTheme.secondary).kccContentCard() }
        case .unavailable:
            Text("convoy.errorGeneric").foregroundStyle(KccTheme.secondary).kccContentCard()
        case .failed(let error):
            SettingsStateCard(title: "convoy.createTitle", message: ConvoyCreateStrings.errorKey(error), symbol: "exclamationmark.circle",
                retryTitle: "convoy.friendsRetry", onRetry: { Task { await coordinator.load() } })
        }
    }

    @ViewBuilder
    private var friendRows: some View {
        if let friendsCoordinator {
            switch friendsCoordinator.status {
            case .loading:
                HStack(spacing: KccSpacing.s3) {
                    ProgressView()
                    Text("friends.listTitle").foregroundStyle(KccTheme.secondary)
                }
            case .error(let error):
                SettingsStateCard(title: "convoy.pickFriendsTitle", message: FriendsScreenStrings.statusErrorKey(error), symbol: "person.2",
                    retryTitle: "convoy.friendsRetry", onRetry: { Task { await friendsCoordinator.load() } })
            case .loaded(let friends, _, _, _):
                if friends.isEmpty { Text("convoy.noFriends").foregroundStyle(KccTheme.secondary) }
                else { ForEach(friends) { friendButton($0) } }
            }
        } else { Text("convoy.errorGeneric").foregroundStyle(KccTheme.secondary) }
    }

    private func friendButton(_ friend: FriendSummary) -> some View {
        let selected = selectedUids.contains(friend.uid)
        return Button {
            if selected { selectedUids.remove(friend.uid) }
            else if selectedUids.count < Self.maxInvitees { selectedUids.insert(friend.uid) }
        } label: { ConvoyFriendChoiceLabel(friend: friend, selected: selected) }
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
              case .ready(let hasActiveConvoy) = coordinator.availability else { return false }
        return !hasActiveConvoy
    }
    private static let maxInvitees = 50
}
