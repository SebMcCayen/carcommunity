import SwiftUI

struct EventChatScreen: View {
    let coordinator: EventChatCoordinator
    let accessCoordinator: EventDetailCoordinator

    @State private var draft = ""
    @State private var reportMessage: EventChatMessage?

    private let quickEmoji = ["🙂", "😂", "😢", "👍", "❤️", "👀", "👑"]
    private let quickEmojiKeys = [
        "chat.quickEmoji.happy",
        "chat.quickEmoji.laughing",
        "chat.quickEmoji.sad",
        "chat.quickEmoji.thumbsUp",
        "chat.quickEmoji.heart",
        "chat.quickEmoji.eyes",
        "chat.quickEmoji.crown",
    ]

    var body: some View {
        Group {
            if accessCoordinator.canOpenEventChat {
                VStack(spacing: 0) {
                    messageArea
                    Divider()
                    statusArea
                    quickEmojiRow
                    composer
                }
            } else {
                Text("chat.accessLost")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(KccSpacing.s4)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(Text("chat.eventChatTitle"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: accessCoordinator.canOpenEventChat) {
            coordinator.setAccess(accessCoordinator.canOpenEventChat)
        }
        .confirmationDialog(
            Text("chat.reportReasonPrompt"),
            isPresented: Binding(
                get: { reportMessage != nil },
                set: { if !$0 { reportMessage = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let message = reportMessage {
                ForEach(ChatReportReason.allCases, id: \.self) { reason in
                    Button(reasonKey(reason)) {
                        reportMessage = nil
                        Task { await coordinator.report(message, reason: reason) }
                    }
                }
            }
            Button("chat.replyCancel", role: .cancel) { reportMessage = nil }
        }
    }

    @ViewBuilder
    private var messageArea: some View {
        switch coordinator.messagesState {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed:
            VStack(spacing: KccSpacing.s3) {
                Text("chat.errorLoading")
                    .multilineTextAlignment(.center)
                Button("events.retry") { coordinator.reload() }
                    .buttonStyle(.borderedProminent)
            }
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded(let messages):
            if messages.isEmpty {
                Text("chat.empty")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                messageList(messages)
            }
        }
    }

    private func messageList(_ messages: [EventChatMessage]) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: KccSpacing.s2) {
                    ForEach(messages) { message in
                        EventChatMessageRow(
                            message: message,
                            isOwn: message.authorUserId == coordinator.currentUserId
                        )
                        .id(message.id)
                        .contextMenu {
                            if message.authorUserId != coordinator.currentUserId,
                                !message.isRemoved,
                                !message.isAutoHidden
                            {
                                Button {
                                    reportMessage = message
                                } label: {
                                    Label("chat.reportMessage", systemImage: "flag")
                                }
                            }
                        }
                    }
                }
                .padding(KccSpacing.s3)
            }
            .onAppear { scrollToNewest(messages, proxy: proxy, animated: false) }
            .onChange(of: messages.last?.id) { _, _ in
                scrollToNewest(messages, proxy: proxy, animated: true)
            }
        }
    }

    private func scrollToNewest(
        _ messages: [EventChatMessage],
        proxy: ScrollViewProxy,
        animated: Bool
    ) {
        guard let id = messages.last?.id else { return }
        if animated {
            withAnimation { proxy.scrollTo(id, anchor: .bottom) }
        } else {
            proxy.scrollTo(id, anchor: .bottom)
        }
    }

    @ViewBuilder
    private var statusArea: some View {
        if coordinator.sendState == .failed {
            Button("chat.errorSending") { coordinator.resetSendFailure() }
                .font(.system(size: KccTypeScale.bodySm))
                .foregroundStyle(KccPalette.errorRed)
                .padding(.horizontal, KccSpacing.s3)
                .padding(.top, KccSpacing.s2)
                .accessibilityHint(Text("events.retry"))
        }
        if coordinator.reportState == .done {
            Button("chat.reportSubmitted") { coordinator.resetReport() }
                .font(.system(size: KccTypeScale.bodySm))
                .padding(.horizontal, KccSpacing.s3)
                .padding(.top, KccSpacing.s2)
        } else if coordinator.reportState == .failed {
            Button("chat.reportError") { coordinator.resetReport() }
                .font(.system(size: KccTypeScale.bodySm))
                .foregroundStyle(KccPalette.errorRed)
                .padding(.horizontal, KccSpacing.s3)
                .padding(.top, KccSpacing.s2)
        }
    }

    private var quickEmojiRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: KccSpacing.s2) {
                ForEach(Array(quickEmoji.enumerated()), id: \.offset) { index, emoji in
                    Button {
                        Task { _ = await coordinator.send(emoji) }
                    } label: {
                        Text(emoji)
                            .font(.title2)
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .disabled(coordinator.sendState == .sending)
                    .accessibilityLabel(Text(LocalizedStringKey(quickEmojiKeys[index])))
                }
            }
            .padding(.horizontal, KccSpacing.s3)
        }
        .accessibilityLabel(Text("chat.quickEmojiRow"))
    }

    private var composer: some View {
        HStack(spacing: KccSpacing.s2) {
            TextField("chat.inputPlaceholder", text: $draft, axis: .vertical)
                .lineLimit(1...4)
                .textInputAutocapitalization(.sentences)
                .autocorrectionDisabled(false)
                .padding(.horizontal, KccSpacing.s3)
                .padding(.vertical, KccSpacing.s2)
                .background(KccPalette.softSand.opacity(0.4))
                .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
                .onChange(of: draft) { _, value in
                    if value.count > eventChatMessageMaxLength {
                        draft = String(value.prefix(eventChatMessageMaxLength))
                    }
                }
            Button("chat.sendButton") {
                let message = draft
                Task {
                    if await coordinator.send(message) { draft = "" }
                }
            }
            .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
            .disabled(coordinator.sendState == .sending || !EventChat.isSendable(draft))
            .frame(minHeight: 44)
        }
        .padding(KccSpacing.s3)
    }

    private func reasonKey(_ reason: ChatReportReason) -> LocalizedStringKey {
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

private struct EventChatMessageRow: View {
    let message: EventChatMessage
    let isOwn: Bool
    @State private var revealed = false

    var body: some View {
        VStack(alignment: isOwn ? .trailing : .leading, spacing: KccSpacing.s1) {
            if !isOwn {
                Text(displayName)
                    .font(.system(size: KccTypeScale.caption, weight: KccTypeScale.semibold))
                    .foregroundStyle(.secondary)
            }
            messageBody
                .padding(.horizontal, KccSpacing.s3)
                .padding(.vertical, KccSpacing.s2)
                .background(isOwn ? KccPalette.crownGold.opacity(0.25) : KccPalette.softSand.opacity(0.6))
                .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
        }
        .frame(maxWidth: .infinity, alignment: isOwn ? .trailing : .leading)
    }

    private var displayName: String {
        let value = message.authorDisplayName?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let value, !value.isEmpty { return value }
        return String(localized: "chat.unknownAuthor")
    }

    @ViewBuilder
    private var messageBody: some View {
        if message.isRemoved {
            Text("chat.removedMessage")
                .italic()
                .foregroundStyle(.secondary)
        } else if message.isAutoHidden && !revealed {
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                Text("chat.reportedHidden")
                    .italic()
                    .foregroundStyle(.secondary)
                Button("chat.showReportedMessage") { revealed = true }
                    .frame(minHeight: 44)
            }
        } else {
            Text(message.message)
                .textSelection(.enabled)
        }
    }
}
