import SwiftUI

/// The shared channel message-thread view — used by both ``CommunityChatScreen``
/// and ``ConvoyChatScreen`` (the iOS analog of Android's `ChannelChatContent`
/// shared by `CommunityChannelRoute`/`ConvoyChannelRoute`). A dumb read over the
/// pure ``ChannelChatCoordinator``: it renders the merged thread (server messages
/// + optimistic bubbles), an older-page control, the composer, and — gated on
/// `chatReplies` — the reply banner and per-message actions.
///
/// The `coordinator` is nil in a config-less build (no Firebase); the view then
/// renders the unavailable placeholder rather than crashing, the same seam every
/// Firebase-backed surface honors.
struct ChannelChatScreen: View {
    @Environment(\.chatReadingActive) private var readingActive
    let coordinator: ChannelChatCoordinator?
    /// The channel's title (`chatHub.tabCommunity` for community, the convoy's
    /// name — a runtime string, so passed as ``Text`` rather than a localization
    /// key — for a convoy).
    let title: Text
    let showsNavigationTitle: Bool
    let protectCommunity: Bool
    let onViewProfile: ((String, String?) -> Void)?
    @State private var expandedGroups: Set<String> = []
    @State private var cooldownSeconds = 0

    init(
        coordinator: ChannelChatCoordinator?,
        title: Text,
        showsNavigationTitle: Bool = true,
        protectCommunity: Bool = false,
        onViewProfile: ((String, String?) -> Void)? = nil
    ) {
        self.coordinator = coordinator
        self.title = title
        self.showsNavigationTitle = showsNavigationTitle
        self.protectCommunity = protectCommunity
        self.onViewProfile = onViewProfile
    }

    var body: some View {
        MessageReactionHost(room: (coordinator?.reactionRoom ?? MessageReactionRoom(scope: "community", roomID: "global")), uid: (coordinator?.currentUserId ?? "")) { reactionContent }
            .id((coordinator?.reactionRoom ?? MessageReactionRoom(scope: "community", roomID: "global")).key + ":" + (coordinator?.currentUserId ?? ""))
    }

    private var reactionContent: some View {
        Group {
            if showsNavigationTitle {
                content.navigationTitle(title)
            } else {
                content
            }
        }
            .kccReadingSurface(pageBackground: true)
            .navigationBarTitleDisplayMode(.inline)
            .task { coordinator?.start() }
            .onChange(of: readingActive, initial: true) { _, active in
                coordinator?.setReadingActive(active)
            }
            .onDisappear { coordinator?.setReadingActive(false) }
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            messageArea(coordinator)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    VStack(spacing: 0) {
                        Divider()
                        if coordinator.chatRepliesEnabled, let target = coordinator.replyTarget {
                            replyBanner(target, coordinator: coordinator)
                        }
                        composer(coordinator)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    .background(KccTheme.surface)
                }
                .task(id: coordinator.cooldown.deadlineToken) {
                    // Keep the native editor outside a periodically rebuilt
                    // TimelineView. Idle conversations need no timer at all.
                    var remaining = coordinator.cooldown.remainingSeconds
                    cooldownSeconds = remaining
                    while remaining > 0 {
                        do { try await Task.sleep(for: .milliseconds(250)) }
                        catch { return }
                        remaining = coordinator.cooldown.remainingSeconds
                        if cooldownSeconds != remaining { cooldownSeconds = remaining }
                    }
                }

        } else {
            placeholder
        }
    }

    // MARK: - Message area

    @ViewBuilder
    private func messageArea(_ coordinator: ChannelChatCoordinator) -> some View {
        if coordinator.isInitialLoading {
            loadingState
        } else if coordinator.messages.isEmpty {
            emptyState
        } else {
            messageList(coordinator)
        }
    }

    private func messageList(_ coordinator: ChannelChatCoordinator) -> some View {
        let groups = protectCommunity ? CommunityMessageGroups.build(coordinator.messages) :
            coordinator.messages.map { CommunityMessageGroup(messages: [$0]) }
        // Keep each message as its own lazy row. At accessibility text sizes,
        // one expanded group can span several screens and leave the latest
        // message out of the rendered viewport even at the scroll limit.
        let rows = groups.flatMap { group -> [ThreadRow] in
            let expanded = expandedGroups.contains(group.id)
            let messages = expanded ? group.messages[...] : group.messages.suffix(2)
            let messageRows = messages.enumerated().map { index, message in
                ThreadRow.message(message, showSender: index == 0)
            }
            return group.messages.count > 2 ? [.groupToggle(group, expanded: expanded)] + messageRows : messageRows
        }
        return ChatThreadScrollView(latestMessageID: coordinator.messages.last?.id,
                                    latestIsOwn: coordinator.messages.last?.senderUid == coordinator.currentUserId) {
            olderControl(coordinator)
            ForEach(rows) { row in
                switch row {
                case .groupToggle(let group, let expanded):
                    groupToggle(group, expanded: expanded)
                case .message(let message, let showSender):
                    MessageRow(message: message,
                               isOwn: message.senderUid == coordinator.currentUserId,
                               showSender: showSender,
                               collapsedLines: protectCommunity ? 4 : nil,
                               coordinator: coordinator,
                               onViewProfile: onViewProfile)
                        .messageReactions(id: message.id, counts: message.reactionCounts, eligible: message.deliveryState == .sent, isOwn: message.senderUid == coordinator.currentUserId, leadingInset: 48) { messageActions(message, coordinator: coordinator) }
                        .id(message.id)
                }
            }
        }
    }

    private enum ThreadRow: Identifiable {
        enum ID: Hashable {
            case groupToggle(String)
            case message(String)
        }

        case groupToggle(CommunityMessageGroup, expanded: Bool)
        case message(ChannelMessage, showSender: Bool)

        var id: ID {
            switch self {
            case .groupToggle(let group, _): .groupToggle(group.id)
            case .message(let message, _): .message(message.id)
            }
        }
    }

    private func groupToggle(_ group: CommunityMessageGroup, expanded: Bool) -> some View {
        Button {
            if expanded { expandedGroups.remove(group.id) } else { expandedGroups.insert(group.id) }
        } label: {
            if expanded {
                Text("chat.flood.showLess")
            } else {
                Text(String.localizedStringWithFormat(NSLocalizedString("chat.flood.moreFrom", comment: ""),
                    group.messages[0].senderDisplayName ?? String(localized: "chat.unknownAuthor"),
                    Int64(group.messages.count - 2)))
            }
        }
        .accessibilityIdentifier("community-group-toggle-" + group.id)
        .frame(minHeight: 44)
    }

    @ViewBuilder
    private func olderControl(_ coordinator: ChannelChatCoordinator) -> some View {
        switch coordinator.olderPaging {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, KccSpacing.s2)
        case .idle, .failed:
            Button {
                coordinator.loadOlder()
            } label: {
                Text(coordinator.olderPaging == .failed ? "dm.retry" : "dm.loadOlder")
                    .font(.subheadline)
                    .frame(maxWidth: .infinity)
            }
            .padding(.vertical, KccSpacing.s1)
        case .exhausted:
            EmptyView()
        }
    }

    // MARK: - Per-message actions (reply gated; report always available)

    @ViewBuilder
    private func messageActions(
        _ message: ChannelMessage,
        coordinator: ChannelChatCoordinator
    ) -> some View {
        // A failed own bubble offers retry; delivered OTHER-authored messages
        // offer report; reply is gated behind chatReplies.
        if message.deliveryState == .failed, message.sendError?.isRetryable == true {
            Button { coordinator.retry(message) } label: { Text("dm.retry") }
                .disabled(coordinator.cooldown.isBlocked)
        }
        if coordinator.chatRepliesEnabled, message.deliveryState == .sent {
            ReactionReplyAction { coordinator.setReplyTarget(message) }
        }
        if message.deliveryState == .sent, message.senderUid != coordinator.currentUserId {
            ReportMenu(message: message, coordinator: coordinator)
            ReactionBlockAction(targetUID: message.senderUid)
        }
    }

    // MARK: - Reply banner (chatReplies-gated)

    private func replyBanner(
        _ target: ChannelMessage,
        coordinator: ChannelChatCoordinator
    ) -> some View {
        HStack(spacing: KccSpacing.s2) {
            Text(replyingToText(target))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Spacer()
            Button { coordinator.clearReply() } label: {
                Image(systemName: "xmark.circle.fill")
                    .accessibilityLabel(Text("chat.replyCancel"))
            }
        }
        .padding(.horizontal, KccSpacing.s3)
        .padding(.vertical, KccSpacing.s2)
        .background(KccTheme.surface)
    }

    private func replyingToText(_ target: ChannelMessage) -> String {
        let name = target.senderDisplayName ?? String(localized: "chat.unknownAuthor")
        return String.localizedStringWithFormat(
            NSLocalizedString("chat.replyComposerReplyingTo", comment: "Reply composer banner"),
            name
        )
    }

    // MARK: - Composer

    private func composer(_ coordinator: ChannelChatCoordinator) -> some View {
        let remaining = max(cooldownSeconds, coordinator.cooldown.remainingSeconds)
        return VStack(alignment: .leading, spacing: KccSpacing.s1) {
                if remaining > 0 {
                    Text("chat.flood.paused")
                        .font(.footnote)
                        .accessibilityAddTraits(.updatesFrequently)
                    Text(String.localizedStringWithFormat(NSLocalizedString("chat.flood.wait", comment: ""), Int64(remaining)))
                        .font(.footnote)
                        .accessibilityIdentifier("chat-flood-countdown")
                }
                ChannelComposer(
                    draft: Binding(get: { coordinator.draft }, set: { coordinator.draft = $0 }),
                    canSend: remaining == 0 && ChannelThread.isSendable(coordinator.draft) && coordinator.currentUserId != nil,
                    onQuickEmoji: { _ = coordinator.send($0) },
                    quickEmojiEnabled: remaining == 0 && coordinator.currentUserId != nil
                ) {
                    coordinator.sendDraft()
                }
        }
    }

    // MARK: - Placeholder states

    private var loadingState: some View {
        ProgressView()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var emptyState: some View {
        Text("chat.empty")
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var placeholder: some View {
        ChatUnavailableState()
    }
}

/// A bounded editor viewport keeps Send reachable with the keyboard on a
/// narrow phone. The complete draft stays in the coordinator; extra lines scroll
/// inside the native vertical field instead of displacing the action offscreen.
struct ChannelComposer: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Binding var draft: String
    let canSend: Bool
    var onQuickEmoji: ((String) -> Void)? = nil
    var quickEmojiEnabled = true
    let onSend: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            if let onQuickEmoji {
                ChatQuickEmojiRow(enabled: quickEmojiEnabled, onSelect: onQuickEmoji)
            }
            editor
        }
    }

    private var editor: some View {
        HStack(spacing: KccSpacing.s2) {
            TextField("chat.inputPlaceholder", text: $draft, axis: .vertical)
                .textFieldStyle(.plain)
                .font(.body)
                .lineLimit(1...(dynamicTypeSize.isAccessibilitySize ? 1 : 2))
                .padding(.horizontal, KccSpacing.s3)
                .padding(.vertical, KccSpacing.s1)
                .frame(minHeight: 48)
                .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.lg))
                .overlay {
                    RoundedRectangle(cornerRadius: KccRadius.lg).strokeBorder(KccTheme.border)
                        .allowsHitTesting(false)
                }
                .accessibilityIdentifier("chat.composer.input")
            Button(action: onSend) {
                if dynamicTypeSize.isAccessibilitySize {
                    Image(systemName: "paperplane.fill").font(.system(size: 20, weight: .semibold))
                        .frame(width: 48, height: 48)
                } else {
                    Text("chat.sendButton").font(.body.weight(.semibold)).frame(minHeight: 48)
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(KccPalette.crownGold)
            .foregroundStyle(KccTheme.onGold)
            .accessibilityLabel(Text("chat.sendButton"))
            .accessibilityIdentifier("chat.composer.send")
            .disabled(!canSend)
            ChatKeyboardDismissButton()
        }
        .padding(KccSpacing.s2)
    }
}

/// One message bubble: the sender name (for other-authored messages), the text,
/// an optional reply quote, and a delivery-status line for the caller's own
/// pending/failed bubbles.
struct MessageRow: View {
    let message: ChannelMessage
    let isOwn: Bool
    var showSender: Bool
    var collapsedLines: Int?
    let coordinator: ChannelChatCoordinator?
    let onViewProfile: ((String, String?) -> Void)?

    init(
        message: ChannelMessage,
        isOwn: Bool,
        showSender: Bool = true,
        collapsedLines: Int? = nil,
        coordinator: ChannelChatCoordinator? = nil,
        onViewProfile: ((String, String?) -> Void)? = nil
    ) {
        self.message = message
        self.isOwn = isOwn
        self.showSender = showSender
        self.collapsedLines = collapsedLines
        self.coordinator = coordinator
        self.onViewProfile = onViewProfile
    }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            if !isOwn {
                if showSender {
                    senderAvatar
                } else {
                    Color.clear.frame(width: 44, height: 1).accessibilityHidden(true)
                }
            }
            VStack(alignment: isOwn ? .trailing : .leading, spacing: KccSpacing.s1) {
                if !isOwn && showSender {
                    senderNameView
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(KccChatColors.readingAccent)
                }
                if let reply = message.replyTo {
                    Text(reply.textPreview)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(KccSpacing.s2)
                        .background(KccTheme.surface)
                        .clipShape(RoundedRectangle(cornerRadius: KccRadius.sm))
                }
                ExpandableChannelText(text: message.text, messageID: message.id, collapsedLines: collapsedLines)
                    .padding(.horizontal, KccSpacing.s3)
                    .padding(.vertical, KccSpacing.s2)
                    .background(isOwn ? KccPalette.crownGold : KccTheme.surface)
                    .foregroundStyle(isOwn ? KccTheme.onGold : KccTheme.text)
                    .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
                if let timestamp = message.createdAt {
                    Text(timestamp, style: .time).font(.caption).foregroundStyle(KccTheme.secondary)
                }
                statusLine
            }
        }
        .frame(maxWidth: .infinity, alignment: isOwn ? .trailing : .leading)
    }

    private var senderName: String {
        message.senderDisplayName ?? String(localized: "chat.unknownAuthor")
    }

    private var openProfile: (() -> Void)? {
        guard let onViewProfile,
              !message.senderUid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return nil }
        return { onViewProfile(message.senderUid, message.senderDisplayName) }
    }

    @ViewBuilder
    private var senderAvatar: some View {
        let avatar = ChannelSenderAvatar(avatarPath: message.senderAvatarPath, coordinator: coordinator)
            .frame(width: 44, height: 44, alignment: .topLeading)
        if let openProfile {
            Button(action: openProfile) {
                avatar.contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: String.localizedStringWithFormat(
                NSLocalizedString("channel.openSenderProfile", comment: ""), senderName)))
            .accessibilityIdentifier("chat.sender.profile.\(message.id)")
        } else {
            avatar.accessibilityHidden(true)
        }
    }

    @ViewBuilder
    private var senderNameView: some View {
        if let openProfile {
            Button(action: openProfile) {
                Text(verbatim: senderName)
            }
            .buttonStyle(.plain)
        } else {
            Text(verbatim: senderName)
        }
    }

    @ViewBuilder
    private var statusLine: some View {
        switch message.deliveryState {
        case .sending:
            Text("dm.statusSending")
                .font(.footnote)
                .foregroundStyle(.secondary)
        case .failed:
            Text(Self.sendErrorKey(message.sendError))
                .font(.footnote)
                .foregroundStyle(KccPalette.errorRed)
        case .sent:
            EmptyView()
        }
    }

    /// Maps a ``ChannelSendError`` to its localized message. Reuses the `dm.*`
    /// send-error strings, which map 1:1 to the five channel send-error cases
    /// (community/convoy chat has no per-reason `chat.*` keys of its own).
    static func sendErrorKey(_ error: ChannelSendError?) -> LocalizedStringKey {
        switch error {
        case .signedOut: return "dm.sendErrorSignedOut"
        case .notMember: return "dm.sendErrorNotMember"
        case .invalid: return "dm.sendErrorInvalid"
        case .cannotDeliver: return "dm.sendErrorCannotDeliver"
        case .rateLimited: return "chat.flood.paused"
        case .generic, .none: return "dm.statusFailedRetry"
        }
    }
}

/// Resolve only visible incoming avatars, using the channel's shared URL cache.
private struct ChannelSenderAvatar: View {
    let avatarPath: String?
    let coordinator: ChannelChatCoordinator?
    @State private var url: URL?

    var body: some View {
        AsyncImage(url: url) { phase in
            if let image = phase.image {
                image.resizable().scaledToFill()
            } else {
                Image(systemName: "person.fill")
                    .foregroundStyle(KccTheme.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(KccTheme.subtle)
            }
        }
        .frame(width: 32, height: 32)
        .clipShape(Circle())
        .task(id: avatarPath) {
            url = nil
            let resolved = await coordinator?.avatarURL(for: avatarPath)
            guard !Task.isCancelled else { return }
            url = resolved
        }
    }
}

/// The report submenu — one entry per ``ChatReportReason``. Fire-and-forget:
/// the outcome is not surfaced per-reason (matching the callable's refusal to
/// reveal report state).
private struct ReportMenu: View {
    @Environment(\.messageReactionStore) private var reactionStore
    let message: ChannelMessage
    let coordinator: ChannelChatCoordinator

    var body: some View {
        Menu {
            ForEach(ChatReportReason.allCases, id: \.self) { reason in
                Button {
                    reactionStore?.dismiss()
                    Task { await coordinator.report(message, reason: reason) }
                } label: {
                    Text(Self.reasonKey(reason))
                }
            }
        } label: {
            Label("chat.reportMessage", systemImage: "flag")
        }
    }

    static func reasonKey(_ reason: ChatReportReason) -> LocalizedStringKey {
        // The localization key is `chat.reportReason.<wire>`. A static literal per
        // case (not string interpolation, which LocalizedStringKey would turn into
        // a format argument rather than part of the key).
        switch reason {
        case .harassment: return "chat.reportReason.harassment"
        case .hateOrAbuse: return "chat.reportReason.hate_or_abuse"
        case .spam: return "chat.reportReason.spam"
        case .unsafeDriving: return "chat.reportReason.unsafe_driving"
        case .privacy: return "chat.reportReason.privacy"
        case .other: return "chat.reportReason.other"
        }
    }
}


/// Measure rendered lines rather than counting characters: emoji, wrapping and
/// Dynamic Type can truncate even a short string. The measuring copy is hidden
/// from accessibility and never changes the visible layout.
private struct ExpandableChannelText: View {
    let text: String
    let messageID: String
    let collapsedLines: Int?
    @State private var expanded = false
    @State private var fullHeight: CGFloat = 0
    @State private var visibleHeight: CGFloat = 0

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            Text(text)
                .font(.body)
                .lineLimit(expanded ? nil : collapsedLines)
                .accessibilityIdentifier("chat.message.text." + messageID)
                .background {
                    GeometryReader { geometry in
                        Color.clear.preference(key: ChannelVisibleTextHeight.self, value: geometry.size.height)
                    }
                }
                .background {
                    if collapsedLines != nil {
                        Text(text).font(.body)
                            .fixedSize(horizontal: false, vertical: true)
                            .hidden()
                            .background {
                                GeometryReader { geometry in
                                    Color.clear.preference(key: ChannelFullTextHeight.self, value: geometry.size.height)
                                }
                            }
                            .accessibilityHidden(true)
                    }
                }
            if collapsedLines != nil && (expanded || fullHeight > visibleHeight + 1) {
                Button { expanded.toggle() } label: {
                    Text(expanded ? "chat.flood.showLess" : "chat.flood.showMore")
                }
                .font(.footnote)
                .frame(minHeight: 44)
                .accessibilityIdentifier("chat-body-toggle-" + messageID)
            }
        }
        .onPreferenceChange(ChannelVisibleTextHeight.self) { visibleHeight = $0 }
        .onPreferenceChange(ChannelFullTextHeight.self) { fullHeight = $0 }
        .onChange(of: text) { _, _ in expanded = false }
    }
}

private struct ChannelVisibleTextHeight: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
private struct ChannelFullTextHeight: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
