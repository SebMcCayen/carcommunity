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
    let coordinator: ChannelChatCoordinator?
    /// The channel's title (`chatHub.tabCommunity` for community, the convoy's
    /// name — a runtime string, so passed as ``Text`` rather than a localization
    /// key — for a convoy).
    let title: Text

    @State private var draft = ""

    var body: some View {
        content
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .task { coordinator?.start() }
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            VStack(spacing: 0) {
                messageArea(coordinator)
                Divider()
                if coordinator.chatRepliesEnabled, let target = coordinator.replyTarget {
                    replyBanner(target, coordinator: coordinator)
                }
                composer(coordinator)
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
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s2) {
                olderControl(coordinator)
                ForEach(coordinator.messages) { message in
                    MessageRow(
                        message: message,
                        isOwn: message.senderUid == coordinator.currentUserId
                    )
                    .contextMenu { messageActions(message, coordinator: coordinator) }
                }
            }
            .padding(KccSpacing.s3)
        }
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
                    .font(.system(size: KccTypeScale.bodySm))
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
        }
        if coordinator.chatRepliesEnabled, message.deliveryState == .sent {
            Button { coordinator.setReplyTarget(message) } label: {
                Label("chat.replyQuoteHeaderAction", systemImage: "arrowshape.turn.up.left")
            }
        }
        if message.deliveryState == .sent, message.senderUid != coordinator.currentUserId {
            ReportMenu(message: message, coordinator: coordinator)
        }
    }

    // MARK: - Reply banner (chatReplies-gated)

    private func replyBanner(
        _ target: ChannelMessage,
        coordinator: ChannelChatCoordinator
    ) -> some View {
        HStack(spacing: KccSpacing.s2) {
            Text(replyingToText(target))
                .font(.system(size: KccTypeScale.bodySm))
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
        .background(KccPalette.softSand.opacity(0.5))
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
        HStack(spacing: KccSpacing.s2) {
            TextField("chat.inputPlaceholder", text: $draft, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(1...4)
                .padding(.horizontal, KccSpacing.s3)
                .padding(.vertical, KccSpacing.s2)
                .background(KccPalette.softSand.opacity(0.4))
                .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
            Button {
                // Only clear the draft when something was actually sent — a
                // signed-out caller (no currentUserId) has send(_:) ignored and
                // must not silently lose their draft.
                if coordinator.send(draft) != nil {
                    draft = ""
                }
            } label: {
                Text("chat.sendButton")
                    .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
            }
            .disabled(!ChannelThread.isSendable(draft) || coordinator.currentUserId == nil)
        }
        .padding(KccSpacing.s3)
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
        Text("chatHub.unavailable")
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// One message bubble: the sender name (for other-authored messages), the text,
/// an optional reply quote, and a delivery-status line for the caller's own
/// pending/failed bubbles.
private struct MessageRow: View {
    let message: ChannelMessage
    let isOwn: Bool

    var body: some View {
        VStack(alignment: isOwn ? .trailing : .leading, spacing: KccSpacing.s1) {
            if !isOwn {
                Text(message.senderDisplayName ?? String(localized: "chat.unknownAuthor"))
                    .font(.system(size: KccTypeScale.caption, weight: KccTypeScale.semibold))
                    .foregroundStyle(.secondary)
            }
            if let reply = message.replyTo {
                Text(reply.textPreview)
                    .font(.system(size: KccTypeScale.caption))
                    .foregroundStyle(.secondary)
                    .padding(KccSpacing.s2)
                    .background(KccPalette.softSand.opacity(0.4))
                    .clipShape(RoundedRectangle(cornerRadius: KccRadius.sm))
            }
            Text(message.text)
                .font(.system(size: KccTypeScale.bodyMd))
                .padding(.horizontal, KccSpacing.s3)
                .padding(.vertical, KccSpacing.s2)
                .background(isOwn ? KccPalette.crownGold.opacity(0.25) : KccPalette.softSand.opacity(0.6))
                .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
            statusLine
        }
        .frame(maxWidth: .infinity, alignment: isOwn ? .trailing : .leading)
    }

    @ViewBuilder
    private var statusLine: some View {
        switch message.deliveryState {
        case .sending:
            Text("dm.statusSending")
                .font(.system(size: KccTypeScale.caption))
                .foregroundStyle(.secondary)
        case .failed:
            Text(Self.sendErrorKey(message.sendError))
                .font(.system(size: KccTypeScale.caption))
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
        case .generic, .none: return "dm.statusFailedRetry"
        }
    }
}

/// The report submenu — one entry per ``ChatReportReason``. Fire-and-forget:
/// the outcome is not surfaced per-reason (matching the callable's refusal to
/// reveal report state).
private struct ReportMenu: View {
    let message: ChannelMessage
    let coordinator: ChannelChatCoordinator

    var body: some View {
        Menu {
            ForEach(ChatReportReason.allCases, id: \.self) { reason in
                Button {
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
