import Foundation
import Observation

/// Orchestrates the DM inbox: subscribes the live conversation listener AND
/// the caller's `blockVisibility` hidden-set listener, and combines the two
/// so a mutually-hidden counterparty's row is dropped — the iOS port of
/// Android's `ConversationListRoute` wiring + the block `combine` inside
/// `FirebaseDmRepository.observeConversations`.
///
/// TWO independent signals drop a blocked row, mirroring
/// `dm.listConversations` on the server: the conversation document's own
/// `blockedPair` marker (dropped inside the repository mapping, so it covers
/// the window before the mirror catches up and the mirror's cap) and the
/// hidden set applied here. Pure Swift so both are unit-testable with fakes.
@MainActor
@Observable
final class ConversationsCoordinator {
    private let repository: ConversationsRepository
    private let blockVisibility: BlockVisibilityRepository
    private let uid: String

    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var blockSubscription: Task<Void, Never>?

    /// The raw inbox state (blocked-pair-marker rows already dropped by the
    /// repository), before the hidden-set filter.
    private var rawState: DmConversationsState = .loading
    private var hiddenUids: Set<String> = []

    /// The filtered, UI-facing inbox state.
    private(set) var state: DmConversationsState = .loading

    init(
        repository: ConversationsRepository,
        blockVisibility: BlockVisibilityRepository,
        uid: String
    ) {
        self.repository = repository
        self.blockVisibility = blockVisibility
        self.uid = uid
    }

    deinit {
        subscription?.cancel()
        blockSubscription?.cancel()
    }

    /// Begins observing on first appearance. Idempotent: a second call keeps
    /// the live subscriptions and their current state.
    func start() {
        guard subscription == nil else { return }
        subscribeBlocks()
        subscribe()
    }

    /// The "try again" affordance — Android's `retryKey++`: tears the inbox
    /// listener down, returns to loading, and re-subscribes from scratch (a
    /// transient failure — offline, or a not-yet-active composite index — can
    /// then recover without leaving the user on a dead-end error).
    func retry() {
        subscribe()
    }

    private func subscribe() {
        subscription?.cancel()
        rawState = .loading
        state = .loading
        let stream = repository.observeConversations(uid: uid)
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.rawState = snapshot
                self.applyFilter()
            }
        }
    }

    private func subscribeBlocks() {
        blockSubscription?.cancel()
        let stream = blockVisibility.observeHiddenUids()
        blockSubscription = Task { [weak self] in
            for await hidden in stream {
                guard !Task.isCancelled, let self else { return }
                self.hiddenUids = hidden
                self.applyFilter()
            }
        }
    }

    /// Combines the latest raw inbox with the latest hidden set. Loading and
    /// error pass through untouched; a loaded inbox drops every row whose
    /// counterparty is in the hidden set (a hidden DM thread is hidden for
    /// BOTH parties).
    private func applyFilter() {
        switch rawState {
        case .loaded(let conversations):
            state = .loaded(
                BlockVisibility.filterHiddenAuthors(conversations, hidden: hiddenUids) {
                    $0.otherUser.uid
                }
            )
        case .loading, .error:
            state = rawState
        }
    }
}

/// Which thread the "start a new dialogue" picker opens for a chosen friend,
/// and whether that thread already exists in the caller's inbox (Android:
/// `DmOpenTarget`). `isExisting` is informational only — a self-derived
/// ``dmPairId(_:_:)`` resolves the same document either way.
struct DmOpenTarget: Equatable, Sendable {
    let uid: String
    let displayName: String?
    let isExisting: Bool
}

/// Pure logic behind the DM inbox's "start a new dialogue" friend picker
/// (Android: `NewDialogue`). Kept free of SwiftUI/Firebase so the
/// eligibility filtering and the selection → open-target mapping are
/// unit-testable, and so the picker can never disagree with the other "pick
/// a friend" surfaces on who is eligible.
enum NewDialogue {
    /// The friends a NEW DM may be started with: established friends only (a
    /// pending request is not yet a friend and cannot receive a DM),
    /// blank-uid rows dropped, name-ordered for a scannable picker.
    static func targets(_ data: FriendsData) -> [FriendSummary] {
        FriendShareTargets.from(data)
    }

    /// Resolves which thread the picked friend opens, given the caller's
    /// current inbox. When a conversation already exists, its inbox display
    /// name is preferred over the friend-row name (the inbox card is the name
    /// the member just saw). A blank/absent name on the existing row never
    /// shadows a usable friend-row name; when NEITHER source has a usable
    /// name, `displayName` is nil — never a blank string — so the thread
    /// title falls back to the neutral placeholder.
    static func openTarget(
        for friend: FriendSummary,
        in conversations: [DmConversation]
    ) -> DmOpenTarget {
        let existing = conversations.first { $0.otherUser.uid == friend.uid }
        let existingName = existing?.otherUser.displayName.nonBlank
        return DmOpenTarget(
            uid: friend.uid,
            displayName: existingName ?? friend.displayName.nonBlank,
            isExisting: existing != nil
        )
    }
}

extension Optional where Wrapped == String {
    fileprivate var nonBlank: String? {
        guard let self,
            !self.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return nil }
        return self
    }
}

/// UI-facing state of the "start a new dialogue" friend picker (Android:
/// `NewDialogueState`).
enum NewDialogueState: Equatable, Sendable {
    case loading
    /// The friends a new DM may be started with, already filtered and
    /// name-ordered. An EMPTY list is a valid, non-error state — the member
    /// simply has no friends yet.
    case ready([FriendSummary])
    /// The friends snapshot failed to load; the picker offers a retry.
    case error
}

/// Loads the member's friends for the DM inbox's "start a new dialogue"
/// picker (Android: `NewDialogueCoordinator`). There is NO send step:
/// picking a friend just opens (or re-opens) the DM thread with them, which
/// is pure navigation — the chat coordinator derives the pairId and the
/// first sent message creates the document.
@MainActor
@Observable
final class NewDialogueCoordinator {
    private let friends: FriendsRepository

    private(set) var state: NewDialogueState = .loading

    init(friends: FriendsRepository) {
        self.friends = friends
    }

    /// Loads (or reloads, on retry) the eligible friends.
    func load() async {
        state = .loading
        switch await friends.list() {
        case .loaded(let data):
            state = .ready(NewDialogue.targets(data))
        case .failed:
            state = .error
        }
    }
}
