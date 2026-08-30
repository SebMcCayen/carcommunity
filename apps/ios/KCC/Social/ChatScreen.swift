import SwiftUI

/// A 1:1 DM thread — the iOS port of Android's `ChatScreen` core: own vs
/// other message bubbles (chronological, newest at the bottom), a "load
/// earlier" affordance at the top, and a text input + send.
///
/// Send is optimistic: on tap the draft clears immediately and the message
/// appears at once as a "sending" bubble; a bubble that fails shows the
/// specific `dm.sendError*` reason and — only when retrying could help
/// (``DmSendError/isRetryable``) — a "tap to retry" affordance. The user's
/// text is never lost.
///
/// BLOCKING: nothing is filtered at this layer, matching Android — a blocked
/// pair's messages listen is denied outright by firebase/firestore.rules
/// (rendered as an empty thread by the repository), and `dm-sendMessage`
/// refuses with a neutral failed-precondition (`dm.sendErrorCannotDeliver`).
///
/// Exported ready for the shell-wiring PR (``ShellRoute/chat``): the route
/// host wraps it in a `NavigationStack`, builds the coordinator for the
/// (uid, otherUid) pair, and supplies the title name. Android's shared chat
/// extras (day separators, link/location/event chips, long-press moderation
/// sheet, inline replies) ship with the chat-hub slice alongside the shared
/// components they need.
struct ChatScreen: View {
    /// Nil in a config-less build; the screen degrades to a placeholder.
    let coordinator: ChatCoordinator?
    /// The other member's best-known display name (thread title). Nil falls
    /// back to the neutral placeholder.
    let otherName: String?
    /// The caller's uid — decides own vs other bubble alignment.
    let currentUid: String

    @State private var draft = ""

    var body: some View {
        content
            .navigationTitle(titleText)
            .navigationBarTitleDisplayMode(.inline)
            .task { await coordinator?.start() }
    }

    private var titleText: Text {
        if let otherName, !otherName.isEmpty {
            Text(verbatim: otherName)
        } else {
            Text("dm.unknownMember")
        }
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            VStack(spacing: 0) {
                thread(coordinator)
                composer(coordinator)
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

    // MARK: - Thread

    @ViewBuilder
    private func thread(_ coordinator: ChatCoordinator) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: KccSpacing.s2) {
                    if coordinator.canLoadOlder {
                        loadOlderControl(coordinator)
                    }
                    if coordinator.threadLoading && coordinator.messages.isEmpty {
                        ProgressView()
                            .padding(KccSpacing.s4)
                    } else if coordinator.messages.isEmpty {
                        Text("dm.emptyThread")
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(KccSpacing.s6)
                    } else {
                        ForEach(coordinator.messages) { message in
                            MessageBubble(
                                message: message,
                                isOwn: message.senderUid == currentUid,
                                onRetry: { clientId in
                                    Task { await coordinator.retry(clientId: clientId) }
                                }
                            )
                            .id(message.id)
                        }
                    }
                }
                .padding(KccSpacing.s3)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: coordinator.messages.last?.id) { _, newest in
                // Keep the view pinned to the newest message as sends land
                // and live updates arrive.
                if let newest {
                    withAnimation {
                        proxy.scrollTo(newest, anchor: .bottom)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func loadOlderControl(_ coordinator: ChatCoordinator) -> some View {
        if coordinator.pageStatus == .loading {
            ProgressView()
                .padding(KccSpacing.s2)
        } else {
            Button {
                Task { await coordinator.loadOlder() }
            } label: {
                Text("dm.loadOlder")
                    .font(.system(size: KccTypeScale.bodySm))
            }
            .buttonStyle(.bordered)
        }
    }

    // MARK: - Composer

    private func composer(_ coordinator: ChatCoordinator) -> some View {
        HStack(spacing: KccSpacing.s2) {
            TextField(text: $draft, axis: .vertical) {
                Text("dm.inputPlaceholder")
            }
            .lineLimit(1...4)
            .textFieldStyle(.roundedBorder)
            Button {
                let text = draft
                // Clear the draft immediately — the optimistic bubble is the
                // feedback, not a blocked composer.
                draft = ""
                Task { await coordinator.send(text: text) }
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: KccTypeScale.headingLg))
            }
            .accessibilityLabel(Text("dm.send"))
            .disabled(!DmThreadLogic.isSendable(draft))
        }
        .padding(KccSpacing.s3)
        .background(.bar)
    }
}

/// One message bubble: own messages right-aligned in brand gold, incoming
/// left-aligned on the subtle surface; an optimistic bubble carries its
/// sending/failed status underneath.
private struct MessageBubble: View {
    let message: DmMessage
    let isOwn: Bool
    let onRetry: (String) -> Void

    var body: some View {
        HStack {
            if isOwn { Spacer(minLength: KccSpacing.s10) }
            VStack(alignment: isOwn ? .trailing : .leading, spacing: KccSpacing.s1) {
                bubble
                status
            }
            if !isOwn { Spacer(minLength: KccSpacing.s10) }
        }
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            if let replyTo = message.replyTo {
                quoteHeader(replyTo)
            }
            Text(verbatim: message.text)
                .font(.system(size: KccTypeScale.bodyMd))
        }
        .padding(.horizontal, KccSpacing.s3)
        .padding(.vertical, KccSpacing.s2)
        .background(isOwn ? KccPalette.crownGold.opacity(0.35) : Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
    }

    /// Minimal quote header for a delivered inline reply (the full
    /// tap-to-scroll quote component ships with the chat-hub slice).
    private func quoteHeader(_ replyTo: DmReplyTo) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1 / 2) {
            if let name = replyTo.senderDisplayName, !name.isEmpty {
                Text(verbatim: name)
                    .font(.system(size: KccTypeScale.caption, weight: KccTypeScale.semibold))
            }
            Text(verbatim: replyTo.textPreview)
                .font(.system(size: KccTypeScale.caption))
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .padding(KccSpacing.s2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.primary.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: KccRadius.sm))
    }

    @ViewBuilder
    private var status: some View {
        switch message.deliveryState {
        case .sending:
            Text("dm.statusSending")
                .font(.system(size: KccTypeScale.caption))
                .foregroundStyle(.secondary)
        case .failed:
            VStack(alignment: .trailing, spacing: KccSpacing.s1 / 2) {
                Text(ChatScreenStrings.sendErrorKey(message.sendError ?? .generic))
                    .font(.system(size: KccTypeScale.caption))
                    .foregroundStyle(KccPalette.errorRed)
                // A retry is offered only when re-sending the SAME message
                // could plausibly succeed; terminal failures (signed out, not
                // a member, invalid, cannot-deliver) show the reason without
                // a pointless retry that would fail the same way.
                if (message.sendError ?? .generic).isRetryable, let clientId = message.clientId {
                    Button {
                        onRetry(clientId)
                    } label: {
                        Text("dm.statusFailedRetry")
                            .font(.system(size: KccTypeScale.caption, weight: KccTypeScale.semibold))
                    }
                    .buttonStyle(.borderless)
                }
            }
        case .sent:
            if let millis = message.createdAtMillis {
                Text(
                    Date(timeIntervalSince1970: Double(millis) / 1000),
                    format: .dateTime.hour().minute()
                )
                .font(.system(size: KccTypeScale.caption))
                .foregroundStyle(.secondary)
            }
        }
    }
}

/// The `dm.sendError*` key for each mapped send failure — the same
/// per-category strings Android renders. ``DmSendError/cannotDeliver`` stays
/// deliberately neutral (never revealing not-friends vs blocked). Pure so
/// the mapping is unit-testable.
enum ChatScreenStrings {
    static func sendErrorKey(_ error: DmSendError) -> LocalizedStringKey {
        switch error {
        case .signedOut: return "dm.sendErrorSignedOut"
        case .notMember: return "dm.sendErrorNotMember"
        case .invalid: return "dm.sendErrorInvalid"
        case .cannotDeliver: return "dm.sendErrorCannotDeliver"
        case .generic: return "dm.sendErrorGeneric"
        }
    }
}

// MARK: - Previews

#Preview("Config-less") {
    NavigationStack {
        ChatScreen(coordinator: nil, otherName: nil, currentUid: "me")
    }
}

#Preview("Thread") {
    NavigationStack {
        ChatScreen(
            coordinator: ChatCoordinator(
                repository: PreviewConversationsRepository(
                    thread: .loaded([
                        DmMessage(
                            id: "m1",
                            senderUid: "u1",
                            text: "Tja! Kör du ikväll?",
                            createdAtMillis: 1_760_000_000_000,
                            createdAtIso: "2025-10-09T08:53:20.000Z"
                        ),
                        DmMessage(
                            id: "m2",
                            senderUid: "me",
                            text: "Absolut, ses vid macken 19:00",
                            createdAtMillis: 1_760_000_060_000,
                            createdAtIso: "2025-10-09T08:54:20.000Z"
                        ),
                    ])
                ),
                selfUid: "me",
                otherUid: "u1"
            ),
            otherName: "GT86_swe",
            currentUid: "me"
        )
    }
}
