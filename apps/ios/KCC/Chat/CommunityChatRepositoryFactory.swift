import FirebaseCore
import Foundation

/// Test data cannot replace an authenticated repository: this fixture only
/// exists in Debug and only when no Firebase app/config has been installed.
enum CommunityChatRepositoryFactory {
    static func make() -> (any CommunityChatRepository)? {
        #if DEBUG
        if FirebaseApp.app() == nil, ProcessInfo.processInfo.arguments.contains("-KCCUITestChatThread") {
            return ConfiglessChatThreadRepository(
                compactSeries: ProcessInfo.processInfo.arguments.contains("-KCCUITestChatFlood"))
        }
        #endif
        return FirebaseCommunityChatRepository.createIfAvailable()
    }
}

#if DEBUG
private actor ConfiglessChatThreadRepository: CommunityChatRepository {
    private var messages: [ChannelMessage]

    init(compactSeries: Bool) {
        // History/keyboard tests need genuinely scrollable normal conversation.
        // Only the flood fixture puts all forty messages in rapid sender runs.
        let spacing = compactSeries ? 1 : 60
        messages = (0..<40).map { index in
            let date = Date(timeIntervalSince1970: Double(1_760_000_000 + index * spacing))
            return ChannelMessage(id: "fixture-\(index)", senderUid: "fixture-other",
                text: "Fixture message \(index)", senderDisplayName: "Testmedlem",
                createdAt: date, createdAtIso: ChannelTime.isoString(date))
        }
    }
    private var streams: [UUID: AsyncStream<ChannelMessagesState>.Continuation] = [:]
    func avatarDownloadURL(for avatarPath: String) async -> URL? { nil }
    nonisolated func currentUserId() -> String? { "fixture-self" }
    nonisolated func observeMessages() -> AsyncStream<ChannelMessagesState> {
        AsyncStream { continuation in
            let token = UUID()
            Task { await self.attach(continuation, token: token) }
            continuation.onTermination = { _ in Task { await self.detach(token) } }
        }
    }
    private func attach(_ continuation: AsyncStream<ChannelMessagesState>.Continuation, token: UUID) {
        streams[token] = continuation
        continuation.yield(.loaded(messages))
    }
    private func detach(_ token: UUID) { streams[token] = nil }
    func post(text: String, mentionedUids: [String], clientId: String?, replyToMessageId: String?) async -> ChannelSendResult {
        let id = clientId ?? UUID().uuidString
        messages.append(ChannelMessage(id: id, senderUid: "fixture-self", text: text,
            senderDisplayName: "Testförare", createdAt: Date(), clientId: clientId))
        for continuation in streams.values { continuation.yield(.loaded(messages)) }
        return .sent(messageId: id, mentionedUids: [])
    }
    func loadOlder(before: String) async -> ChannelOlderResult {
        .loaded(ChannelMessagesPage(messages: [], nextBefore: nil, hasMore: false))
    }
    func markRead() async {}
    func report(messageId: String, reason: ChatReportReason) async -> ChannelReportResult { .reported }
}
#endif
