import Foundation

enum GroupDriveStatus: String, CaseIterable, Sendable {
    case joined
    case onTheWay = "on_the_way"
    case arrived
    case left

    static let updatable: [Self] = [.joined, .onTheWay, .arrived]
}

struct GroupDriveParticipant: Identifiable, Equatable, Sendable {
    let uid: String
    let displayName: String?
    let status: GroupDriveStatus

    var id: String { uid }
}

enum GroupDriveRosterSnapshot: Equatable, Sendable {
    case loaded([GroupDriveParticipant])
    case failed(code: String?)
}

enum GroupDriveOwnStatusSnapshot: Equatable, Sendable {
    case loaded(GroupDriveStatus?)
    case failed(code: String?)
}

enum GroupDriveActionState: Equatable, Sendable {
    case idle
    case working
    case failed
}

enum GroupDrive {
    static let rosterLimit = 200

    static func canJoin(eventStatus: EventStatus?, rsvp: RsvpStatus?) -> Bool {
        eventStatus == .published && (rsvp == .going || rsvp == .maybe)
    }

    static func isParticipating(_ status: GroupDriveStatus?) -> Bool {
        status != nil && status != .left
    }

    static func activeParticipants(
        _ participants: [GroupDriveParticipant]
    ) -> [GroupDriveParticipant] {
        participants
            .filter { $0.status != .left }
            .sorted {
                let left = $0.displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let right = $1.displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if left.localizedCaseInsensitiveCompare(right) == .orderedSame {
                    return $0.uid < $1.uid
                }
                return left.localizedCaseInsensitiveCompare(right) == .orderedAscending
            }
    }
}
