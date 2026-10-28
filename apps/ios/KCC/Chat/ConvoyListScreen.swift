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
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
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
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                if !grouped.ongoing.isEmpty {
                    Text("chatHub.convoyOngoingHeader").font(.title3.bold()).accessibilityAddTraits(.isHeader)
                    ForEach(grouped.ongoing) { row(for: $0) }
                }
                if !grouped.past.isEmpty {
                    Text("chatHub.convoyHistoryHeader").font(.title3.bold()).accessibilityAddTraits(.isHeader)
                    ForEach(grouped.past) { row(for: $0) }
                }
            }.padding(KccSpacing.s4)
        }
    }

    private func row(for convoy: ChatConvoy) -> some View {
        NavigationLink(value: ConvoyChatRoute(convoyId: convoy.convoyId, title: convoy.title)) {
            ConvoyChatRow(convoy: convoy).kccContentCard().contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func errorState(_ coordinator: ConvoyListCoordinator) -> some View {
        ScrollView {
            SettingsStateCard(title: "convoy.title", message: "chatHub.convoysError", symbol: "car.2",
                retryTitle: "chatHub.convoysRetry", onRetry: coordinator.reload).padding(KccSpacing.s4)
        }
    }

    private func message(_ key: LocalizedStringKey) -> some View {
        SocialReadingState(title: "convoy.title", message: key, symbol: "car.2")
    }
}

/// The value a tapped convoy row pushes.
struct ConvoyChatRoute: Hashable, Sendable {
    let convoyId: String
    let title: String?
}

/// One convoy row: a member/name-derived title, a status badge, and a member
/// count — mirroring Android's `ConvoyRowFormat`-built row.
struct ConvoyChatRow: View {
    let convoy: ChatConvoy

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            VStack(alignment: .leading, spacing: KccSpacing.s2) {
                HStack(alignment: .top, spacing: KccSpacing.s3) {
                    Text(verbatim: rowTitle).font(.headline).fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(KccTheme.secondary).accessibilityHidden(true)
                }
                statusBadge
            }
            Text(verbatim: membersText)
                .font(.subheadline).foregroundStyle(KccTheme.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
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
        Text(Self.statusKey(convoy.status)).font(.footnote.weight(.semibold))
            .foregroundStyle(KccTheme.driver).padding(KccSpacing.s2)
            .background(KccTheme.subtle, in: Capsule())
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
