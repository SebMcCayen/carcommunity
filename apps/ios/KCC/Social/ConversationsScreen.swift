import SwiftUI

/// The DM inbox (conversation list) — the iOS port of Android's
/// `ConversationListScreen` + `ConversationListRoute`: live rows (name,
/// last-message preview, time, unread badge) with a "start a new dialogue"
/// friend picker when a friends repository is wired.
///
/// Exported ready for the shell-wiring PR (``ShellRoute/conversations``):
/// the route host supplies `onOpenConversation`, which receives the resolved
/// peer uid + best-known display name and opens ``ShellRoute/chat``. Until
/// then it defaults to nil and rows render inert.
struct ConversationsScreen: View {
    /// Nil in a config-less build; the screen degrades to a placeholder.
    let coordinator: ConversationsCoordinator?
    /// Builds the "start a new dialogue" picker's coordinator. Nil when no
    /// friends repository is wired — the picker affordance is then hidden
    /// (never a dead control).
    var makeNewDialogueCoordinator: (() -> NewDialogueCoordinator)?
    /// Opens (or re-opens) the 1:1 thread with the given member.
    var onOpenConversation: ((_ uid: String, _ displayName: String?) -> Void)?

    @State private var showPicker = false

    var body: some View {
        content
            .navigationTitle(Text("dm.title"))
            .task { coordinator?.start() }
            .toolbar {
                // Compose-new-message affordance (Android renders a FAB; the
                // iOS convention is a toolbar compose button).
                if canStartNew {
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            showPicker = true
                        } label: {
                            Image(systemName: "square.and.pencil")
                        }
                        .accessibilityLabel(Text("dm.newDialogue.action"))
                    }
                }
            }
            .sheet(isPresented: $showPicker) {
                if let makeNewDialogueCoordinator, let onOpenConversation {
                    NewDialogueSheet(
                        coordinator: makeNewDialogueCoordinator(),
                        onPick: { friend in
                            showPicker = false
                            let target = NewDialogue.openTarget(
                                for: friend,
                                in: loadedConversations
                            )
                            // A blank uid would open a dead thread; the picker
                            // already drops blank-uid friends, this is
                            // belt-and-braces.
                            if !target.uid.isEmpty {
                                onOpenConversation(target.uid, target.displayName)
                            }
                        }
                    )
                }
            }
    }

    private var canStartNew: Bool {
        coordinator != nil && makeNewDialogueCoordinator != nil && onOpenConversation != nil
    }

    /// The inbox rows currently loaded, used to resolve whether a picked
    /// friend re-opens an existing conversation or starts a new one. Empty
    /// while loading/errored — a pick then simply opens a fresh thread.
    private var loadedConversations: [DmConversation] {
        guard case .loaded(let conversations) = coordinator?.state else { return [] }
        return conversations
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            switch coordinator.state {
            case .loading:
                VStack(spacing: KccSpacing.s3) {
                    ProgressView()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .error:
                VStack(spacing: KccSpacing.s3) {
                    Text("dm.loadError")
                        .multilineTextAlignment(.center)
                    Button {
                        coordinator.retry()
                    } label: {
                        Text("dm.retry")
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(KccSpacing.s4)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .loaded(let conversations):
                if conversations.isEmpty {
                    Text("dm.empty")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(KccSpacing.s4)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    list(conversations)
                }
            }
        } else {
            VStack(spacing: KccSpacing.s2) {
                Text("dm.title")
                    .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.semibold))
                Text("common.placeholder")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func list(_ conversations: [DmConversation]) -> some View {
        List {
            ForEach(conversations) { conversation in
                Button {
                    onOpenConversation?(
                        conversation.otherUser.uid,
                        conversation.otherUser.displayName
                    )
                } label: {
                    ConversationRow(conversation: conversation)
                }
                .disabled(onOpenConversation == nil)
            }
        }
        .listStyle(.plain)
    }
}

/// One inbox row: counterparty name, last-message preview, time, unread
/// badge.
private struct ConversationRow: View {
    let conversation: DmConversation

    var body: some View {
        HStack(spacing: KccSpacing.s3) {
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                nameText
                    .font(.system(
                        size: KccTypeScale.bodyMd,
                        weight: conversation.unreadCount > 0
                            ? KccTypeScale.semibold : KccTypeScale.regular
                    ))
                    .foregroundStyle(.primary)
                if let preview = conversation.lastMessage {
                    Text(verbatim: preview.text)
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: KccSpacing.s1) {
                if let millis = conversation.lastMessageAtMillis {
                    Text(
                        Date(timeIntervalSince1970: Double(millis) / 1000),
                        format: .dateTime.hour().minute()
                    )
                    .font(.system(size: KccTypeScale.caption))
                    .foregroundStyle(.secondary)
                }
                if conversation.unreadCount > 0 {
                    Text(unreadBadgeText)
                        .font(.system(size: KccTypeScale.caption, weight: KccTypeScale.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, KccSpacing.s2)
                        .padding(.vertical, KccSpacing.s1 / 2)
                        .background(KccPalette.errorRed)
                        .clipShape(Capsule())
                        .accessibilityLabel(Text(verbatim: unreadAccessibilityText))
                }
            }
        }
        .padding(.vertical, KccSpacing.s1)
    }

    /// Trimmed-first, so a whitespace-only name never renders as a blank
    /// (effectively unlabeled) inbox row.
    private var nameText: Text {
        if let name = conversation.otherUser.displayName.trimmedNonBlank {
            Text(verbatim: name)
        } else {
            Text("dm.unknownMember")
        }
    }

    /// Caps the badge at "99+" (Android's `UnreadBadge`).
    private var unreadBadgeText: String {
        conversation.unreadCount > 99 ? "99+" : "\(conversation.unreadCount)"
    }

    /// "%1$lld unread" (dm.unreadCount) for accessibility.
    private var unreadAccessibilityText: String {
        String.localizedStringWithFormat(
            NSLocalizedString("dm.unreadCount", comment: "Unread count on an inbox row"),
            conversation.unreadCount
        )
    }
}

/// The "start a new dialogue" friend picker sheet (Android:
/// `NewDialogueSheet`). Picking a friend simply reports it upward — opening
/// the thread is pure navigation.
struct NewDialogueSheet: View {
    let coordinator: NewDialogueCoordinator
    let onPick: (FriendSummary) -> Void

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(Text("dm.newDialogue.title"))
                .navigationBarTitleDisplayMode(.inline)
                .task { await coordinator.load() }
        }
        .presentationDetents([.medium, .large])
    }

    @ViewBuilder
    private var content: some View {
        switch coordinator.state {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error:
            VStack(spacing: KccSpacing.s3) {
                Text("dm.newDialogue.error")
                    .multilineTextAlignment(.center)
                Button {
                    Task { await coordinator.load() }
                } label: {
                    Text("dm.newDialogue.retry")
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .ready(let friends):
            if friends.isEmpty {
                Text("dm.newDialogue.empty")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(KccSpacing.s4)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    Section {
                        ForEach(friends) { friend in
                            Button {
                                onPick(friend)
                            } label: {
                                if let name = friend.displayName.trimmedNonBlank {
                                    Text(verbatim: name)
                                } else {
                                    Text("dm.newDialogue.unnamedFriend")
                                }
                            }
                            .foregroundStyle(.primary)
                        }
                    } header: {
                        Text("dm.newDialogue.subtitle")
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
    }
}

// MARK: - Previews

#Preview("Config-less") {
    NavigationStack {
        ConversationsScreen(coordinator: nil)
    }
}

#Preview("Loaded") {
    NavigationStack {
        ConversationsScreen(
            coordinator: ConversationsCoordinator(
                repository: PreviewConversationsRepository(
                    inbox: .loaded([
                        DmConversation(
                            conversationId: "me__u1",
                            otherUser: DmUser(uid: "u1", displayName: "GT86_swe", avatarPath: nil),
                            lastMessage: DmMessagePreview(
                                text: "Ses vid macken kl 19?",
                                senderUid: "u1",
                                createdAtMillis: 1_760_000_000_000
                            ),
                            unreadCount: 2,
                            lastMessageAtMillis: 1_760_000_000_000
                        ),
                        DmConversation(
                            conversationId: "me__u2",
                            otherUser: DmUser(uid: "u2", displayName: "Åsa", avatarPath: nil),
                            lastMessage: DmMessagePreview(
                                text: "Snygg bil!",
                                senderUid: "me",
                                createdAtMillis: 1_759_000_000_000
                            ),
                            unreadCount: 0,
                            lastMessageAtMillis: 1_759_000_000_000
                        ),
                    ])
                ),
                blockVisibility: PreviewBlockVisibilityRepository(),
                uid: "me"
            ),
            onOpenConversation: { _, _ in }
        )
    }
}

/// Scripted ``ConversationsRepository`` for previews.
final class PreviewConversationsRepository: ConversationsRepository, @unchecked Sendable {
    private let inbox: DmConversationsState
    private let thread: DmThreadState

    init(inbox: DmConversationsState = .loaded([]), thread: DmThreadState = .loaded([])) {
        self.inbox = inbox
        self.thread = thread
    }

    func observeConversations(uid: String) -> AsyncStream<DmConversationsState> {
        let inbox = inbox
        return AsyncStream { $0.yield(inbox) }
    }

    func observeThread(conversationId: String) -> AsyncStream<DmThreadState> {
        let thread = thread
        return AsyncStream { $0.yield(thread) }
    }

    func sendMessage(toUid: String, text: String, clientId: String?) async -> DmSendResult {
        .sent(conversationId: dmPairId("me", toUid), messageId: clientId ?? "m")
    }

    func loadOlder(conversationId: String, before: String) async -> DmOlderResult {
        .loaded(DmMessagesPage(messages: [], nextBefore: nil, hasMore: false))
    }

    func markRead(conversationId: String) async {}
}

/// Hide-nothing block visibility for previews.
final class PreviewBlockVisibilityRepository: BlockVisibilityRepository, Sendable {
    func observeHiddenUids() -> AsyncStream<Set<String>> {
        AsyncStream { $0.yield([]) }
    }
}
