import Foundation

let eventChatMessageMaxLength = 1_000
let eventChatWindowSize = 50

enum EventChatModerationState: String, Equatable, Sendable {
    case visible
    case autoHidden = "auto_hidden"
    case removed
    case allowed

    static func fromWire(_ value: String?) -> Self {
        Self(rawValue: value ?? "") ?? .visible
    }
}

struct EventChatMessage: Equatable, Sendable, Identifiable {
    let id: String
    let authorUserId: String
    let authorDisplayName: String?
    let message: String
    let moderationState: EventChatModerationState
    let createdAt: Date?

    var isRemoved: Bool { moderationState == .removed }
    var isAutoHidden: Bool { moderationState == .autoHidden }
}

enum EventChatMessagesState: Equatable, Sendable {
    case loading
    case failed
    case loaded([EventChatMessage])
}

enum EventChatSendState: Equatable, Sendable {
    case idle
    case sending
    case failed
}

enum EventChatReportState: Equatable, Sendable {
    case idle
    case reporting
    case done
    case failed
}

enum EventChat {
    static func canParticipate(
        passesMemberGate: Bool,
        eventStatus: EventStatus?,
        rsvp: RsvpStatus?
    ) -> Bool {
        passesMemberGate
            && eventStatus == .published
            && (rsvp == .going || rsvp == .maybe)
    }

    static func isSendable(_ text: String) -> Bool {
        let count = text.trimmingCharacters(in: .whitespacesAndNewlines).count
        return (1...eventChatMessageMaxLength).contains(count)
    }

    static func filterHidden(
        _ messages: [EventChatMessage],
        hiddenUserIds: Set<String>
    ) -> [EventChatMessage] {
        guard !hiddenUserIds.isEmpty else { return messages }
        return messages.filter { !hiddenUserIds.contains($0.authorUserId) }
    }
}
