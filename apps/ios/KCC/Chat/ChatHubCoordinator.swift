import Foundation
import Observation

/// Owns the chat-hub scaffold: the selected tab and the coordinators behind the
/// channel tabs (Community + Convoys). Friends and Notifications keep their own
/// coordinators because they are also standalone features; ``ChatHubScreen``
/// receives those from the signed-in shell. This is the iOS counterpart of
/// Android's `ChatHubContent` state hoisting.
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

    /// Selects a tab. All four are selectable, matching Android's swipeable
    /// pager where every page exists.
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
