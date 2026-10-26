import SwiftUI

private struct ChatReadingActiveKey: EnvironmentKey {
    static let defaultValue = true
}

extension EnvironmentValues {
    var chatReadingActive: Bool {
        get { self[ChatReadingActiveKey.self] }
        set { self[ChatReadingActiveKey.self] = newValue }
    }
}

/// Opaque native Chat destination preserving the four existing channel routes.
/// Driver content is supplied separately by the authenticated shell.
/// The chat hub — a four-tab scaffold
/// (Community / Convoys / Friends / Notifications), the iOS port of Android's
/// `ChatHubContent`. Community, Convoys, the existing 1:1 DMs, and the in-app
/// notification inbox are connected by the signed-in shell. Each tab retains
/// its own unavailable state when its backing service cannot be configured.
///
/// Both the primary tab and retained legacy chatHub route are opaque.
/// A nil coordinator is supported by previews and isolated hosts; individual
/// feature coordinators may also be nil in config-less builds.
struct ChatHubScreen: View {
    @Environment(\.chatReadingActive) private var readingActive
    let coordinator: ChatHubCoordinator?
    let conversationsCoordinator: ConversationsCoordinator?
    let makeNewDialogueCoordinator: (() -> NewDialogueCoordinator)?
    let onOpenConversation: ((_ uid: String, _ displayName: String?) -> Void)?
    let notificationsCoordinator: NotificationsInboxCoordinator?
    let onOpenNotificationSettings: (() -> Void)?
    let onOpenEvents: (() -> Void)?
    /// The host supplies real native Driver content when its adapter is linked.
    let driverContent: AnyView?
    @State private var showsDriver = false

    init(
        coordinator: ChatHubCoordinator?,
        conversationsCoordinator: ConversationsCoordinator? = nil,
        makeNewDialogueCoordinator: (() -> NewDialogueCoordinator)? = nil,
        onOpenConversation: ((_ uid: String, _ displayName: String?) -> Void)? = nil,
        notificationsCoordinator: NotificationsInboxCoordinator? = nil,
        onOpenNotificationSettings: (() -> Void)? = nil,
        onOpenEvents: (() -> Void)? = nil,
        driverContent: AnyView? = nil
    ) {
        self.coordinator = coordinator
        self.conversationsCoordinator = conversationsCoordinator
        self.makeNewDialogueCoordinator = makeNewDialogueCoordinator
        self.onOpenConversation = onOpenConversation
        self.notificationsCoordinator = notificationsCoordinator
        self.onOpenNotificationSettings = onOpenNotificationSettings
        self.onOpenEvents = onOpenEvents
        self.driverContent = driverContent
    }

    var body: some View {
        VStack(spacing: 0) {
            if driverContent != nil {
                sourcePicker
            }
            ZStack {
                chatsContent
                    .opacity(showsDriver ? 0 : 1)
                    .allowsHitTesting(!showsDriver)
                    .accessibilityHidden(showsDriver)
                    .environment(\.chatReadingActive, readingActive && !showsDriver)
                if let driverContent {
                    driverContent
                        .opacity(showsDriver ? 1 : 0)
                        .allowsHitTesting(showsDriver)
                        .accessibilityHidden(!showsDriver)
                        .environment(\.chatReadingActive, readingActive && showsDriver)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .kccReadingSurface()
        .navigationTitle(Text("chatHub.title"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chatHub.opaqueScreen")
    }

    @ViewBuilder
    private var chatsContent: some View {
        if let coordinator {
            VStack(spacing: 0) {
                tabStrip(coordinator)
                if let onOpenEvents {
                    Button(action: onOpenEvents) {
                        Label("shell.socialEvents", systemImage: "calendar")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .font(.subheadline)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .accessibilityIdentifier("chatHub.events")
                }
                Divider().overlay(KccTheme.border)
                tabContent(coordinator)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            unavailable
        }
    }

    private var sourcePicker: some View {
        HStack(spacing: 0) {
            sourceButton("chatHub.tabChats", driver: false)
            sourceButton("chatHub.tabDriver", driver: true)
        }
        .padding(KccSpacing.s1)
        .background(KccTheme.surface, in: Capsule())
        .overlay { Capsule().strokeBorder(KccTheme.border, lineWidth: 1) }
        .padding(KccSpacing.s4)
    }

    private func sourceButton(_ title: LocalizedStringKey, driver: Bool) -> some View {
        Button { showsDriver = driver } label: {
            Text(title).font(.subheadline.weight(.semibold))
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, KccSpacing.s3)
                .padding(.vertical, KccSpacing.s2)
                .frame(maxWidth: .infinity, minHeight: 48)
                .foregroundStyle(showsDriver == driver ? KccTheme.onDriver : KccTheme.text)
                .background(showsDriver == driver ? KccTheme.driver : .clear, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(driver ? "chatHub.source.driver" : "chatHub.source.chats")
        .accessibilityAddTraits(showsDriver == driver ? [.isSelected] : [])
    }

    // MARK: - Tab strip

    private func tabStrip(_ coordinator: ChatHubCoordinator) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
          HStack(spacing: KccSpacing.s2) {
            ForEach(ChatTab.allCases, id: \.rawValue) { tab in
                Button {
                    coordinator.select(tab)
                } label: {
                    VStack(spacing: KccSpacing.s1) {
                        Image(systemName: Self.icon(for: tab))
                            .font(.system(size: 20))
                            .accessibilityHidden(true)  // decorative — the label text below already names the tab
                        Text(Self.label(for: tab))
                            .font(.subheadline)
                            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                            .lineLimit(1)
                    }
                    .frame(minWidth: 72, minHeight: 44)
                    .padding(.horizontal, KccSpacing.s3)
                    .padding(.vertical, KccSpacing.s2)
                    .foregroundStyle(coordinator.selectedTab == tab ? KccTheme.onDriver : KccTheme.text)
                    .background(coordinator.selectedTab == tab ? KccTheme.driver : KccTheme.subtle, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("chatHub.channel.\(tab.rawValue)")
                .accessibilityAddTraits(coordinator.selectedTab == tab ? [.isSelected] : [])
            }
          }
          .padding(KccSpacing.s3)
        }
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chatHub.channels")
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
        case .friends:
            ConversationsScreen(
                coordinator: conversationsCoordinator,
                makeNewDialogueCoordinator: makeNewDialogueCoordinator,
                onOpenConversation: onOpenConversation,
                showsNavigationTitle: false
            )
        case .notifications:
            NotificationsInboxScreen(
                coordinator: notificationsCoordinator,
                onOpenSettings: onOpenNotificationSettings,
                showsNavigationTitle: false
            )
        }
    }

    private var unavailable: some View {
        ChatUnavailableState()
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

/// The actual reading state is not size-capped with the navigation controls.
/// Natural text height inside a scroll view prevents a short viewport from
/// substituting an ellipsis for the end of the recovery message.
struct ChatUnavailableState: View {
    var body: some View {
        ScrollView {
            Text("chatHub.unavailable")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("chatHub.unavailable.message")
                .padding(KccSpacing.s4)
        }
        .scrollBounceBehavior(.basedOnSize)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chatHub.unavailable.scroll")
    }
}

#Preview {
    NavigationStack {
        ChatHubScreen(coordinator: nil)
    }
}
