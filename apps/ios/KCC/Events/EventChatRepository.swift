import Foundation

protocol EventChatRepository: AnyObject, Sendable {
    /// Latest 50 messages, oldest first. Event chat has no paging callable.
    func messages(eventId: String) -> AsyncStream<EventChatMessagesState>

    /// Live `config/featureFlags.chat`, falling back to the contract default (true).
    func enabled() -> AsyncStream<Bool>

    func post(eventId: String, message: String) async throws
    func report(eventId: String, messageId: String, reason: ChatReportReason) async throws
    func currentUserId() -> String?
}
