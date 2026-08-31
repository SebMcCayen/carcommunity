import Foundation
import Observation

/// Owns the chat-hub scaffold: the selected tab and the coordinators behind the
/// two FUNCTIONAL tabs (Community + Convoys). The Friends and Notifications tabs
/// are separate features (the existing DMs / in-app inbox on Android) and wire
/// up when those land — here they render a placeholder, so the hub holds no
/// coordinator for them. The iOS counterpart of Android's `ChatHubContent`
/// state hoisting.
///
/// Built from the two channel repositories (nil in a config-less build, so the
/// hub degrades to placeholders instead of crashing — the ``createIfAvailable``
/// seam every Firebase-backed surface honors). Pure Swift so tab logic is
/// unit-testable.
@MainActor
@Observable
final class ChatHubCoordinator {
    /// nil in a config-less build; the Community tab then shows a placeholder.
    let communityChat: ChannelChatCoordinator?
    /// nil in a config-less build; the Convoys tab then shows a placeholder.
    let convoyList: ConvoyListCoordinator?
    let chatRepliesEnabled: Bool

    private(set) var selectedTab: ChatTab = .defaultTab

    /// - Parameters:
    ///   - communityRepository: the community channel repo, or nil (config-less).
    ///   - convoyRepository: the convoy chat repo, or nil (config-less).
    ///   - chatRepliesEnabled: the `chatReplies` flag, threaded to every
    ///     message-bearing tab (default OFF).
    init(
        communityRepository: CommunityChatRepository?,
        convoyRepository: ConvoyChatRepository?,
        chatRepliesEnabled: Bool = ChatFeatureFlags.chatRepliesDefault
    ) {
        self.chatRepliesEnabled = chatRepliesEnabled
        self.communityChat = communityRepository.map { repository in
            ChannelChatCoordinator(
                source: CommunityChatSource(repository: repository),
                chatRepliesEnabled: chatRepliesEnabled
            )
        }
        self.convoyList = convoyRepository.map(ConvoyListCoordinator.init(repository:))
    }

    /// Selects a tab. All four are selectable — Friends/Notifications simply
    /// render their placeholder — matching Android's swipeable pager where every
    /// page exists even before its feature is built.
    func select(_ tab: ChatTab) {
        selectedTab = tab
    }

    /// The pure presentation guard the shell must satisfy before showing the hub
    /// popup: it floats over a live map, so a real map must be in front. This
    /// CONSUMES ``ShellNavigation/chatHubAllowed(cover:navigating:)`` (the gate
    /// already living in Shell/ShellNav.swift) rather than re-deriving the rule,
    /// so the two cannot drift. The shell wiring that actually presents the hub
    /// is a later slice; exposing it here keeps the single source of truth in
    /// ShellNav and lets this feature be tested against it.
    nonisolated static func canPresentHub(cover: MapCover, navigating: Bool) -> Bool {
        ShellNavigation.chatHubAllowed(cover: cover, navigating: navigating)
    }
}
