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
        // JavaScript/Zod string limits count UTF-16 code units. Use the same
        // measure as the callable so emoji cannot pass client validation and
        // then be rejected by the backend.
        let count = text.trimmingCharacters(in: .whitespacesAndNewlines).utf16.count
        return (1...eventChatMessageMaxLength).contains(count)
    }

    /// Caps an editor value to the callable's UTF-16 limit without cutting a
    /// surrogate pair or an extended grapheme such as an emoji ZWJ sequence.
    static func truncateToMessageLimit(_ text: String) -> String {
        guard text.utf16.count > eventChatMessageMaxLength else { return text }
        var used = 0
        var end = text.startIndex
        for index in text.indices {
            let next = text.index(after: index)
            let units = text[index..<next].utf16.count
            guard used + units <= eventChatMessageMaxLength else { break }
            used += units
            end = next
        }
        return String(text[..<end])
    }

    static func filterHidden(
        _ messages: [EventChatMessage],
        hiddenUserIds: Set<String>
    ) -> [EventChatMessage] {
        guard !hiddenUserIds.isEmpty else { return messages }
        return messages.filter { !hiddenUserIds.contains($0.authorUserId) }
    }
}
