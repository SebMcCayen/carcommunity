import Foundation
import Observation

/// UI-facing state of the Convoys tab: the caller's chat-eligible convoys,
/// grouped ongoing/past. Empty is a distinct state so the view is a dumb switch
/// (Android derives it inside the list composable). The `convoy-list` callable is
/// a one-shot load, so ``failed`` is a genuine retryable case.
enum ConvoyListUiState: Equatable, Sendable {
    case loading
    case empty
    case loaded(ConvoyRowFormat.Grouped)
    case failed
}

/// Loads and groups the caller's chat-eligible convoys for the Convoys tab —
/// the iOS counterpart of Android's `ConvoyListRoute` wiring. A one-shot load
/// (not a listener): `convoy-list` is a callable, so this refreshes on appear
/// and on explicit retry rather than streaming. Pure Swift so it is testable
/// with a fake ``ConvoyChatRepository``.
@MainActor
@Observable
final class ConvoyListCoordinator {
    private let repository: ConvoyChatRepository

    @ObservationIgnored
    nonisolated(unsafe) private var task: Task<Void, Never>?

    private(set) var state: ConvoyListUiState = .loading

    init(repository: ConvoyChatRepository) {
        self.repository = repository
    }

    deinit {
        task?.cancel()
    }

    /// Loads on first appearance. Idempotent — a second call while already
    /// loaded/loading does not re-fetch (a tab switch must not flash loading).
    func start() {
        guard task == nil else { return }
        load()
    }

    /// The retry affordance — reloads from scratch.
    func reload() {
        load()
    }

    private func load() {
        task?.cancel()
        state = .loading
        task = Task { [weak self, repository] in
            let result = await repository.listConvoys()
            guard !Task.isCancelled, let self else { return }
            self.apply(result)
        }
    }

    private func apply(_ result: ConvoyListState) {
        switch result {
        case .loading:
            state = .loading
        case .error:
            state = .failed
        case .loaded(let convoys):
            if convoys.isEmpty {
                state = .empty
            } else {
                state = .loaded(ConvoyRowFormat.group(convoys))
            }
        }
    }

    /// Builds the chat coordinator for a tapped convoy row — the Convoys tab's
    /// own composition point (the hub passes only this list coordinator, so the
    /// per-convoy chat coordinator is derived from the same repository). A fresh
    /// coordinator per open, like ``EventsCoordinator/makeDetailCoordinator(eventId:)``.
    func makeChatCoordinator(
        convoyId: String,
        chatRepliesEnabled: Bool = ChatFeatureFlags.chatRepliesDefault
    ) -> ChannelChatCoordinator {
        ChannelChatCoordinator(
            source: ConvoyChatSource(repository: repository, convoyId: convoyId),
            chatRepliesEnabled: chatRepliesEnabled
        )
    }
}
