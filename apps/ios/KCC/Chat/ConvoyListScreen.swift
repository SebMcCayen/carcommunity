import SwiftUI

/// The Convoys tab — the caller's chat-eligible convoys grouped Ongoing / History,
/// each row opening that convoy's chat. The iOS slice of Android's
/// `ConvoyListScreen`. A dumb switch over ``ConvoyListCoordinator``; the
/// list→chat push lives inside this feature's own NavigationStack.
struct ConvoyListScreen: View {
    let coordinator: ConvoyListCoordinator?
    let chatRepliesEnabled: Bool

    var body: some View {
        content
            .task { coordinator?.start() }
            .navigationDestination(for: ConvoyChatRoute.self) { route in
                ConvoyChatScreen(
                    coordinator: coordinator?.makeChatCoordinator(
                        convoyId: route.convoyId,
                        chatRepliesEnabled: chatRepliesEnabled
                    ),
                    convoyTitle: route.title
                )
            }
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            switch coordinator.state {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .empty:
                message("chatHub.convoysEmpty")
            case .failed:
                errorState(coordinator)
            case .loaded(let grouped):
                list(grouped)
            }
        } else {
            message("chatHub.unavailable")
        }
    }

    private func list(_ grouped: ConvoyRowFormat.Grouped) -> some View {
        List {
            if !grouped.ongoing.isEmpty {
                Section {
                    ForEach(grouped.ongoing) { row(for: $0) }
                } header: {
                    Text("chatHub.convoyOngoingHeader")
                }
            }
            if !grouped.past.isEmpty {
                Section {
                    ForEach(grouped.past) { row(for: $0) }
                } header: {
                    Text("chatHub.convoyHistoryHeader")
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private func row(for convoy: ChatConvoy) -> some View {
        NavigationLink(value: ConvoyChatRoute(convoyId: convoy.convoyId, title: convoy.title)) {
            ConvoyRow(convoy: convoy)
        }
    }

    private func errorState(_ coordinator: ConvoyListCoordinator) -> some View {
        VStack(spacing: KccSpacing.s3) {
            Text("chatHub.convoysError")
                .multilineTextAlignment(.center)
            Button { coordinator.reload() } label: {
                Text("chatHub.convoysRetry")
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func message(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// The value a tapped convoy row pushes.
struct ConvoyChatRoute: Hashable, Sendable {
    let convoyId: String
    let title: String?
}

/// One convoy row: a member/name-derived title, a status badge, and a member
/// count — mirroring Android's `ConvoyRowFormat`-built row.
private struct ConvoyRow: View {
    let convoy: ChatConvoy

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            HStack(spacing: KccSpacing.s2) {
                Text(rowTitle)
                    .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
                    .lineLimit(1)
                statusBadge
            }
            Text(membersText)
                .font(.system(size: KccTypeScale.bodySm))
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, KccSpacing.s1)
    }

    /// The row title: explicit convoy name, else a summary of member names, else
    /// `chatHub.convoyUntitled`.
    private var rowTitle: String {
        if let title = convoy.title, !title.isEmpty { return title }
        let label = ConvoyRowFormat.memberLabel(names: convoy.memberNames)
        if label.shownNames.isEmpty {
            return String(localized: "chatHub.convoyUntitled")
        }
        let names = label.shownNames.joined(separator: ", ")
        if label.overflow > 0 {
            let overflow = String.localizedStringWithFormat(
                NSLocalizedString("chatHub.convoyMemberOverflow", comment: "Convoy member overflow count"),
                label.overflow
            )
            return "\(names) \(overflow)"
        }
        return names
    }

    @ViewBuilder
    private var statusBadge: some View {
        Text(Self.statusKey(convoy.status))
            .font(.system(size: KccTypeScale.caption))
            .padding(.horizontal, KccSpacing.s2)
            .padding(.vertical, KccSpacing.s1 / 2)
            .background(KccPalette.softSand.opacity(0.6))
            .clipShape(Capsule())
    }

    private var membersText: String {
        String.localizedStringWithFormat(
            NSLocalizedString("chatHub.convoyMembers", comment: "Convoy member count"),
            convoy.memberCount
        )
    }

    static func statusKey(_ status: String) -> LocalizedStringKey {
        switch status {
        case "active": return "chatHub.convoyActiveBadge"
        case "ended": return "chatHub.convoyEndedBadge"
        default: return "chatHub.convoyFormingBadge"
        }
    }
}
