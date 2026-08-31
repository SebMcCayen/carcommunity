import SwiftUI

/// The chat hub opened from the map chat bubble — a four-tab scaffold
/// (Community / Convoys / Friends / Notifications), the iOS port of Android's
/// `ChatHubContent`. Community and Convoys are FUNCTIONAL here; Friends (the
/// existing 1:1 DMs) and Notifications (the in-app inbox) are separate features
/// that render a placeholder until they land.
///
/// Exported ready for a later shell-wiring PR: the shell presents this behind
/// ``ChatHubCoordinator/canPresentHub(cover:navigating:)`` (which consumes the
/// unmodified `ShellNavigation.chatHubAllowed` gate) via `ShellRoute.chatHub`.
/// The `coordinator` is nil in a config-less build; each functional tab then
/// degrades to its own placeholder.
struct ChatHubScreen: View {
    let coordinator: ChatHubCoordinator?

    var body: some View {
        if let coordinator {
            VStack(spacing: 0) {
                tabStrip(coordinator)
                Divider()
                tabContent(coordinator)
            }
            .navigationTitle(Text("chatHub.title"))
            .navigationBarTitleDisplayMode(.inline)
        } else {
            unavailable
                .navigationTitle(Text("chatHub.title"))
        }
    }

    // MARK: - Tab strip

    private func tabStrip(_ coordinator: ChatHubCoordinator) -> some View {
        HStack(spacing: 0) {
            ForEach(ChatTab.allCases, id: \.rawValue) { tab in
                Button {
                    coordinator.select(tab)
                } label: {
                    VStack(spacing: KccSpacing.s1) {
                        Image(systemName: Self.icon(for: tab))
                            .accessibilityHidden(true)  // decorative — the label text below already names the tab
                        Text(Self.label(for: tab))
                            .font(.system(size: KccTypeScale.caption))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, KccSpacing.s2)
                    .foregroundStyle(coordinator.selectedTab == tab ? KccPalette.crownGold : Color.secondary)
                }
                .accessibilityAddTraits(coordinator.selectedTab == tab ? [.isSelected] : [])
            }
        }
    }

    // MARK: - Tab content

    // Tab content is PLAIN views — the hub relies on the SINGLE navigation
    // container its host provides (the shell's route host, or the preview's
    // NavigationStack), rather than nesting a NavigationStack per tab. Nesting
    // stacks under the out-of-stack tab strip mis-renders the nav bar and would
    // double up once the hub is presented inside the shell's own stack. The
    // hub's own `chatHub.title` therefore governs the bar; tab content sets no
    // navigation title of its own.
    @ViewBuilder
    private func tabContent(_ coordinator: ChatHubCoordinator) -> some View {
        switch coordinator.selectedTab {
        case .community:
            CommunityChatScreen(coordinator: coordinator.communityChat)
        case .convoys:
            ConvoyListScreen(
                coordinator: coordinator.convoyList,
                chatRepliesEnabled: coordinator.chatRepliesEnabled
            )
        case .friends, .notifications:
            // Separate features (DMs / in-app inbox); they wire up when those
            // land. Scaffold shows the neutral unavailable notice for now.
            deferredTab
        }
    }

    private var deferredTab: some View {
        Text("chatHub.unavailable")
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var unavailable: some View {
        Text("chatHub.unavailable")
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Tab chrome

    static func label(for tab: ChatTab) -> LocalizedStringKey {
        switch tab {
        case .community: return "chatHub.tabCommunity"
        case .convoys: return "chatHub.tabConvoys"
        case .friends: return "chatHub.tabFriends"
        case .notifications: return "chatHub.tabNotifications"
        }
    }

    static func icon(for tab: ChatTab) -> String {
        switch tab {
        case .community: return "bubble.left.and.bubble.right"
        case .convoys: return "car.2"
        case .friends: return "person.2"
        case .notifications: return "bell"
        }
    }
}

#Preview {
    NavigationStack {
        ChatHubScreen(coordinator: nil)
    }
}
