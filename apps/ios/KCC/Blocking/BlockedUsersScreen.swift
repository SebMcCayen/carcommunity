import SwiftUI

/// Caller-owned block management. The list contains only users the caller
/// blocked; the symmetric hidden set is deliberately not displayed because it
/// does not reveal direction and must remain a filtering primitive.
struct BlockedUsersScreen: View {
    let onBack: () -> Void
    @State private var coordinator: BlockingCoordinator
    @State private var confirmationTarget: BlockedUser?
    @State private var unblockRequest: UnblockRequest?

    init(uid: String?, onBack: @escaping () -> Void) {
        self.init(
            onBack: onBack,
            coordinator: BlockingCoordinator(
                repository: FirebaseBlockingRepository.createIfAvailable(),
                uid: uid
            )
        )
    }

    init(onBack: @escaping () -> Void, coordinator: BlockingCoordinator) {
        self.onBack = onBack
        _coordinator = State(initialValue: coordinator)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s4) {
            Button(action: onBack) {
                Label("profile.back", systemImage: "chevron.backward")
                    .font(.body)
                    .frame(minHeight: 48)
            }
            .accessibilityIdentifier("blockedUsers.back")
            .padding(.horizontal, KccSpacing.s4)

            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    if case .loaded = coordinator.state {
                        Text("blocking.blockedUsersTitle").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    }
                    if case .failed = coordinator.actionStatus { errorNotice }
                    content
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(KccSpacing.s4)
            }
            .accessibilityIdentifier("blockedUsers.content")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .kccReadingSurface(pageBackground: true)
        .tint(KccTheme.driver)
        .task { coordinator.start() }
        .task(id: unblockRequest?.id) {
            guard let request = unblockRequest else { return }
            await coordinator.unblock(targetUserId: request.targetUserId)
            if !Task.isCancelled { unblockRequest = nil }
        }
        .alert(
            "blocking.unblockConfirmTitle",
            isPresented: Binding(
                get: { confirmationTarget != nil },
                set: { if !$0 { confirmationTarget = nil } }
            ),
            presenting: confirmationTarget
        ) { user in
            Button("blocking.unblockConfirmAction", role: .destructive) {
                coordinator.resetActionStatus()
                unblockRequest = UnblockRequest(targetUserId: user.userId)
                confirmationTarget = nil
            }
            Button("blocking.unblockCancelAction", role: .cancel) {
                confirmationTarget = nil
            }
        } message: { _ in
            Text("blocking.unblockConfirmBody")
        }
    }

    @ViewBuilder
    private var content: some View {
        switch coordinator.state {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityLabel(Text("blocking.blockedUsersTitle"))
        case .unavailable:
            SettingsStateCard(title: "blocking.blockedUsersTitle", message: "blocking.unavailable", symbol: "person.slash")
                .accessibilityIdentifier("blockedUsers.unavailable")
        case .empty:
            SettingsStateCard(title: "blocking.blockedUsersTitle", message: "blocking.blockedUsersEmpty", symbol: "person.slash")
                .accessibilityIdentifier("blockedUsers.empty")
        case .failed:
            SettingsStateCard(title: "blocking.blockedUsersTitle", message: "blocking.errorGeneric", symbol: "person.slash",
                retryTitle: "blocking.retry", onRetry: coordinator.reload)
                .accessibilityIdentifier("blockedUsers.loadError")
        case .loaded(let users):
            LazyVStack(spacing: KccSpacing.s3) {
                ForEach(users) { user in
                    BlockedUserCard(user: user, working: coordinator.actionStatus.isWorking,
                        onRequestUnblock: { confirmationTarget = user })
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("blockedUsers.list")
        }
    }

    private var errorNotice: some View {
        SocialFlow {
            Text("blocking.errorGeneric")
                .font(.body)
                .foregroundStyle(.red)
            SocialFlowSpacer()
            Button { coordinator.resetActionStatus() } label: {
                Text("common.dismiss").frame(minHeight: 48)
            }.buttonStyle(.bordered)
        }
        .kccContentCard()
        .accessibilityIdentifier("blockedUsers.actionError")
    }
}

private struct UnblockRequest: Equatable {
    let id = UUID()
    let targetUserId: String
}

#Preview("Blocked users unavailable") {
    BlockedUsersScreen(
        onBack: {},
        coordinator: BlockingCoordinator(repository: nil, uid: nil)
    )
}
