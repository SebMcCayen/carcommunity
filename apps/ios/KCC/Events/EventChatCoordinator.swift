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
    private var hasAccess = true
    private var accessGeneration = 0

    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    init(repository: EventChatRepository, eventId: String, currentUserId: String) {
        self.repository = repository
        self.eventId = eventId
        self.currentUserId = currentUserId
    }

    deinit { subscription?.cancel() }

    func start() {
        guard hasAccess, subscription == nil else { return }
        subscribe()
    }

    /// Keeps denied history off-screen immediately when the event status,
    /// RSVP, membership gate, or feature flag changes while chat is open.
    func setAccess(_ allowed: Bool) {
        if allowed != hasAccess {
            hasAccess = allowed
            accessGeneration &+= 1
        }
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

    func reload() {
        guard hasAccess else { return }
        subscribe()
    }

    private func subscribe() {
        guard hasAccess else { return }
        subscription?.cancel()
        messagesState = .loading
        let generation = accessGeneration
        let stream = repository.messages(eventId: eventId)
        subscription = Task { [weak self] in
            for await state in stream {
                guard !Task.isCancelled, let self else { return }
                guard self.hasAccess, self.accessGeneration == generation else { return }
                self.messagesState = state
            }
        }
    }

    @discardableResult
    func send(_ text: String) async -> Bool {
        guard hasAccess, sendState != .sending, EventChat.isSendable(text) else { return false }
        let generation = accessGeneration
        sendState = .sending
        do {
            try await repository.post(
                eventId: eventId,
                message: text.trimmingCharacters(in: .whitespacesAndNewlines)
            )
            guard !Task.isCancelled, hasAccess, accessGeneration == generation else { return false }
            sendState = .idle
            return true
        } catch is CancellationError {
            if hasAccess, accessGeneration == generation { sendState = .idle }
            return false
        } catch {
            if hasAccess, accessGeneration == generation { sendState = .failed }
            return false
        }
    }

    func resetSendFailure() {
        if sendState == .failed { sendState = .idle }
    }

    func report(_ message: EventChatMessage, reason: ChatReportReason) async {
        guard hasAccess,
            message.authorUserId != currentUserId,
            !message.isRemoved,
            !message.isAutoHidden,
            reportState != .reporting
        else { return }
        let generation = accessGeneration
        reportState = .reporting
        do {
            try await repository.report(eventId: eventId, messageId: message.id, reason: reason)
            guard !Task.isCancelled, hasAccess, accessGeneration == generation else { return }
            reportState = .done
        } catch is CancellationError {
            if hasAccess, accessGeneration == generation { reportState = .idle }
        } catch {
            if hasAccess, accessGeneration == generation { reportState = .failed }
        }
    }

    func resetReport() { reportState = .idle }
}
