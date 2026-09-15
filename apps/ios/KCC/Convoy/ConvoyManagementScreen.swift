import SwiftUI

struct ConvoyManagementScreen: View {
    @Bindable var coordinator: ConvoyManagementCoordinator
    let onCreate: () -> Void
    let onJoined: (ConvoyItem) -> Void

    var body: some View {
        content
            .navigationTitle("convoy.title")
            .task { await coordinator.load() }
            .refreshable { await coordinator.load() }
    }

    @ViewBuilder
    private var content: some View {
        switch coordinator.state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .unavailable:
            message("convoy.errorGeneric")
        case .failed(let error):
            VStack(spacing: KccSpacing.s3) {
                Text(ConvoyManagementStrings.errorKey(error))
                    .multilineTextAlignment(.center)
                Button("convoy.friendsRetry") { Task { await coordinator.load() } }
                    .buttonStyle(.borderedProminent)
            }
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded(let snapshot):
            list(snapshot)
        }
    }

    private func list(_ snapshot: ConvoyManagementSnapshot) -> some View {
        List {
            Section {
                Button(action: onCreate) {
                    Label("convoy.createAction", systemImage: "plus.circle.fill")
                }
                .disabled(!snapshot.canJoinAnotherConvoy)
                if snapshot.hasActiveConvoy {
                    Text("convoy.alreadyInConvoyCreateHint").foregroundStyle(.secondary)
                } else if !snapshot.isExhaustive {
                    Text("convoy.errorGeneric").foregroundStyle(.secondary)
                    Button("convoy.friendsRetry") { Task { await coordinator.load() } }
                }
            }

            if let error = coordinator.actionError {
                Section {
                    HStack(alignment: .top) {
                        Text(ConvoyManagementStrings.errorKey(error))
                            .foregroundStyle(KccPalette.errorRed)
                        Spacer()
                        Button {
                            coordinator.clearActionError()
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text("notifications.errorDismiss"))
                    }
                }
            }

            if !snapshot.pendingInvites.isEmpty {
                Section("convoy.invitesTitle") {
                    ForEach(snapshot.pendingInvites) { invite in
                        pendingInvite(invite, snapshot: snapshot)
                    }
                }
            }

            Section("convoy.myConvoysTitle") {
                if snapshot.myConvoys.isEmpty {
                    Text("convoy.emptyMine").foregroundStyle(.secondary)
                } else {
                    ForEach(snapshot.myConvoys) { convoyRow($0) }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private func pendingInvite(
        _ convoy: ConvoyItem,
        snapshot: ConvoyManagementSnapshot
    ) -> some View {
        let busy = coordinator.busyConvoyIds.contains(convoy.convoyId)
        return VStack(alignment: .leading, spacing: KccSpacing.s3) {
            convoyHeader(convoy)
            Text(invitedBy(convoy))
                .font(.system(size: KccTypeScale.bodySm))
                .foregroundStyle(.secondary)
            HStack(spacing: KccSpacing.s3) {
                Button("convoy.accept") {
                    Task {
                        if let joined = await coordinator.respond(
                            convoyId: convoy.convoyId,
                            action: .accept
                        ) {
                            onJoined(joined)
                        }
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(busy || !snapshot.canJoinAnotherConvoy)

                Button("convoy.decline", role: .destructive) {
                    Task {
                        await coordinator.respond(convoyId: convoy.convoyId, action: .decline)
                    }
                }
                .buttonStyle(.bordered)
                .disabled(busy)

                if busy { ProgressView() }
            }
            if snapshot.hasActiveConvoy {
                Text("convoy.alreadyInConvoyAcceptHint")
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(.secondary)
            } else if !snapshot.isExhaustive {
                Text("convoy.errorGeneric")
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, KccSpacing.s1)
    }

    private func convoyRow(_ convoy: ConvoyItem) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            convoyHeader(convoy)
            Text(memberCount(convoy))
                .font(.system(size: KccTypeScale.bodySm))
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, KccSpacing.s1)
    }

    private func convoyHeader(_ convoy: ConvoyItem) -> some View {
        HStack {
            Text(convoy.title ?? String(localized: "convoy.untitled"))
                .font(.system(size: KccTypeScale.bodyMd, weight: .semibold))
                .lineLimit(1)
            Spacer()
            Text(statusKey(convoy.status))
                .font(.system(size: KccTypeScale.caption))
                .padding(.horizontal, KccSpacing.s2)
                .padding(.vertical, KccSpacing.s1 / 2)
                .background(KccPalette.softSand.opacity(0.6), in: Capsule())
        }
    }

    private func invitedBy(_ convoy: ConvoyItem) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("convoy.invitedByLabel", comment: "Convoy inviter"),
            convoy.ownerName ?? String(localized: "convoy.unknownMember")
        )
    }

    private func memberCount(_ convoy: ConvoyItem) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("convoy.barMembers", comment: "Accepted convoy member count"),
            convoy.acceptedMemberCount
        )
    }

    private func statusKey(_ status: ConvoyStatus) -> LocalizedStringKey {
        switch status {
        case .forming: "convoy.statusForming"
        case .active: "convoy.statusActive"
        case .ended: "convoy.statusEnded"
        }
    }

    private func message(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
