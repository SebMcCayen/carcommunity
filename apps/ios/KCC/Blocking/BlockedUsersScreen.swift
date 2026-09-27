import SwiftUI

/// Caller-owned block management. The list contains only users the caller
/// blocked; the symmetric hidden set is deliberately not displayed because it
/// does not reveal direction and must remain a filtering primitive.
struct BlockedUsersScreen: View {
    let onBack: () -> Void
    @State private var coordinator: BlockingCoordinator
    @State private var confirmationTarget: BlockedUser?
    @State private var unblockRequest: UnblockRequest?

    init(onBack: @escaping () -> Void) {
        self.init(
            onBack: onBack,
            coordinator: BlockingCoordinator(
                repository: FirebaseBlockingRepository.createIfAvailable(),
                uid: Self.signedInUid()
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
                    .font(.system(size: KccTypeScale.bodyMd))
            }
            .accessibilityIdentifier("blockedUsers.back")

            Text("blocking.blockedUsersTitle")
                .font(.system(size: KccTypeScale.headingLg, weight: .semibold))

            if case .failed = coordinator.actionStatus {
                errorNotice
            }

            content
        }
        .padding(KccSpacing.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(.background, ignoresSafeAreaEdges: .all)
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
            Text("blocking.unavailable")
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("blockedUsers.unavailable")
        case .empty:
            Text("blocking.blockedUsersEmpty")
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("blockedUsers.empty")
        case .failed:
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                Text("blocking.errorGeneric")
                    .foregroundStyle(.red)
                Button("blocking.retry") { coordinator.reload() }
                    .buttonStyle(.bordered)
            }
            .accessibilityIdentifier("blockedUsers.loadError")
        case .loaded(let users):
            ScrollView {
                LazyVStack(spacing: KccSpacing.s3) {
                    ForEach(users) { user in row(user) }
                }
            }
            .accessibilityIdentifier("blockedUsers.list")
        }
    }

    private func row(_ user: BlockedUser) -> some View {
        HStack(spacing: KccSpacing.s3) {
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                Text(user.displayName ?? String(localized: "blocking.unknownUser"))
                    .font(.system(size: KccTypeScale.titleMd, weight: .medium))
                if let blockedAt = user.blockedAt {
                    Text(blockedAt, format: .dateTime.year().month().day())
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button("blocking.unblock") { confirmationTarget = user }
                .buttonStyle(.bordered)
                .disabled(coordinator.actionStatus.isWorking)
                .accessibilityIdentifier("blockedUsers.unblock.\(user.userId)")
        }
        .padding(KccSpacing.s4)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
    }

    private var errorNotice: some View {
        HStack {
            Text("blocking.errorGeneric")
                .font(.system(size: KccTypeScale.bodySm))
                .foregroundStyle(.red)
            Spacer()
            Button("common.dismiss") { coordinator.resetActionStatus() }
                .font(.system(size: KccTypeScale.bodySm))
        }
        .accessibilityIdentifier("blockedUsers.actionError")
    }

    private static func signedInUid() -> String? {
        if case .signedIn(let uid, _)? = FirebaseAuthRepository.createIfAvailable()?.authState {
            return uid
        }
        return nil
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
