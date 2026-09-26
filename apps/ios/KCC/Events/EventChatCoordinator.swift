import Foundation
import Observation

@MainActor
@Observable
final class EventChatCoordinator {
    private let repository: EventChatRepository
    let eventId: String
    let currentUserId: String

    private(set) var messagesState: EventChatMessagesState = .loading
    private(set) var sendState: EventChatSendState = .idle
    private(set) var reportState: EventChatReportState = .idle

    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    init(repository: EventChatRepository, eventId: String, currentUserId: String) {
        self.repository = repository
        self.eventId = eventId
        self.currentUserId = currentUserId
    }

    deinit { subscription?.cancel() }

    func start() {
        guard subscription == nil else { return }
        subscribe()
    }

    /// Keeps denied history off-screen immediately when the event status,
    /// RSVP, membership gate, or feature flag changes while chat is open.
    func setAccess(_ allowed: Bool) {
        if allowed {
            start()
        } else {
            subscription?.cancel()
            subscription = nil
            messagesState = .loaded([])
            sendState = .idle
            reportState = .idle
        }
    }

    func reload() { subscribe() }

    private func subscribe() {
        subscription?.cancel()
        messagesState = .loading
        let stream = repository.messages(eventId: eventId)
        subscription = Task { [weak self] in
            for await state in stream {
                guard !Task.isCancelled, let self else { return }
                self.messagesState = state
            }
        }
    }

    @discardableResult
    func send(_ text: String) async -> Bool {
        guard sendState != .sending, EventChat.isSendable(text) else { return false }
        sendState = .sending
        do {
            try await repository.post(
                eventId: eventId,
                message: text.trimmingCharacters(in: .whitespacesAndNewlines)
            )
            guard !Task.isCancelled else {
                sendState = .idle
                return false
            }
            sendState = .idle
            return true
        } catch is CancellationError {
            sendState = .idle
            return false
        } catch {
            sendState = .failed
            return false
        }
    }

    func resetSendFailure() {
        if sendState == .failed { sendState = .idle }
    }

    func report(_ message: EventChatMessage, reason: ChatReportReason) async {
        guard message.authorUserId != currentUserId,
            !message.isRemoved,
            !message.isAutoHidden,
            reportState != .reporting
        else { return }
        reportState = .reporting
        do {
            try await repository.report(eventId: eventId, messageId: message.id, reason: reason)
            guard !Task.isCancelled else {
                reportState = .idle
                return
            }
            reportState = .done
        } catch is CancellationError {
            reportState = .idle
        } catch {
            reportState = .failed
        }
    }

    func resetReport() { reportState = .idle }
}
