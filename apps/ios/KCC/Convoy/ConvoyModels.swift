import Foundation
import SwiftUI

enum ConvoyStatus: String, Equatable, Sendable {
    case forming
    case active
    case ended
}

enum ConvoyInviteStatus: String, Equatable, Sendable {
    case invited
    case accepted
    case declined
}

enum ConvoyRole: String, Equatable, Sendable {
    case owner
    case member
}

struct ConvoyMember: Equatable, Sendable, Identifiable {
    let uid: String
    let displayName: String?
    let role: ConvoyRole
    let inviteStatus: ConvoyInviteStatus

    var id: String { uid }
}

struct ConvoyViewer: Equatable, Sendable {
    let role: ConvoyRole?
    let inviteStatus: ConvoyInviteStatus

    init(inviteStatus: ConvoyInviteStatus, role: ConvoyRole? = nil) {
        self.role = role
        self.inviteStatus = inviteStatus
    }
}

struct ConvoyItem: Equatable, Sendable, Identifiable {
    let convoyId: String
    let title: String?
    let status: ConvoyStatus
    let members: [ConvoyMember]
    let viewer: ConvoyViewer?
    let createdAt: Date?

    var id: String { convoyId }

    var ownerName: String? {
        members.first(where: { $0.role == .owner })?.displayName
    }

    var acceptedMemberCount: Int {
        members.filter { $0.inviteStatus == .accepted }.count
    }

    var viewerIsOwner: Bool { viewer?.role == .owner }

    var acceptedMembers: [ConvoyMember] {
        members.filter { $0.inviteStatus == .accepted }
    }

    var pendingMembers: [ConvoyMember] {
        members.filter { $0.inviteStatus == .invited }
    }
}

struct ConvoyManagementSnapshot: Equatable, Sendable {
    let convoys: [ConvoyItem]
    let pendingInvites: [ConvoyItem]
    let isExhaustive: Bool

    var myConvoys: [ConvoyItem] {
        let pendingIds = Set(pendingInvites.map(\.convoyId))
        return convoys.filter { !pendingIds.contains($0.convoyId) }
    }

    var hasActiveConvoy: Bool {
        convoys.contains {
            $0.status != .ended && $0.viewer?.inviteStatus == .accepted
        }
    }

    /// False means the capped list cannot prove that an older active
    /// membership does not exist, so joining another convoy must stay blocked.
    var canJoinAnotherConvoy: Bool { !hasActiveConvoy && isExhaustive }
}

enum ConvoyAction: String, Equatable, Sendable {
    case accept
    case decline
}

enum ConvoyActionError: Equatable, Sendable {
    case signedOut
    case notMember
    case invalid
    case notFound
    case inviteGone
    case alreadyInConvoy
    case unresolvedPrecondition
    case notLeader
    case cannotStart
    case alreadyEnded
    case leaveFailed
    case noInvitees
    case generic
}

enum ConvoyLifecycleAction: String, Equatable, Sendable {
    case start
    case end
    case leave
}

enum ConvoyLeaveOutcome: String, Equatable, Sendable {
    case left
    case leftAndEnded = "left_and_ended"
}

struct ConvoyLeaveResult: Equatable, Sendable {
    let outcome: ConvoyLeaveOutcome
    let newLeaderUid: String?
}

struct ConvoyInviteResult: Equatable, Sendable {
    let invitedCount: Int
    let skippedCount: Int
}

enum ConvoyLifecycleResult: Equatable, Sendable {
    case updated(ConvoyItem)
    case left(ConvoyLeaveResult)
    case failed(ConvoyActionError)
}

enum ConvoyInviteMutationResult: Equatable, Sendable {
    case completed(ConvoyInviteResult)
    case failed(ConvoyActionError)
}

enum ConvoyExitChoice: Equatable, Sendable {
    case leaveOrEnd
    case endOnly
    case leaveOnly
    case leaveEndsConvoy
}

enum ConvoyBarLogic {
    static let minimumRemainingMembers = 2

    static func activeConvoy(in snapshot: ConvoyManagementSnapshot) -> ConvoyItem? {
        let joined = snapshot.convoys.filter {
            $0.status != .ended && $0.viewer?.inviteStatus == .accepted
        }
        return joined.first(where: { $0.status == .active }) ?? joined.first
    }

    static func exitChoice(viewerIsOwner: Bool, acceptedMemberCount: Int) -> ConvoyExitChoice {
        let remaining = max(acceptedMemberCount - 1, 0)
        let survives = remaining >= minimumRemainingMembers
        switch (viewerIsOwner, survives) {
        case (true, true): return .leaveOrEnd
        case (true, false): return .endOnly
        case (false, true): return .leaveOnly
        case (false, false): return .leaveEndsConvoy
        }
    }
}

enum ConvoyManagementListResult: Equatable, Sendable {
    case loaded(ConvoyManagementSnapshot)
    case failed(ConvoyActionError)
}

enum ConvoyRespondResult: Equatable, Sendable {
    case updated(ConvoyItem)
    case failed(ConvoyActionError)
}

enum ConvoyManagementParser {
    static let listLimit = 200

    static func parseList(_ data: [String: Any]?) -> ConvoyManagementSnapshot {
        let convoys = parseItems(data?["convoys"])
        let pendingInvites = parseItems(data?["pendingInvites"])
            .filter { $0.status != .ended && $0.viewer?.inviteStatus == .invited }
        return ConvoyManagementSnapshot(
            convoys: convoys,
            pendingInvites: pendingInvites,
            isExhaustive: (data?["convoys"] as? [Any] ?? []).count < listLimit
        )
    }

    static func parseRespond(_ data: [String: Any]?) -> ConvoyRespondResult {
        guard let convoy = parseItem(data?["convoy"]) else { return .failed(.generic) }
        return .updated(convoy)
    }

    static func parseLifecycle(
        _ data: [String: Any]?,
        action: ConvoyLifecycleAction
    ) -> ConvoyLifecycleResult {
        if action == .leave {
            guard let outcomeRaw = data?["outcome"] as? String,
                  let outcome = ConvoyLeaveOutcome(rawValue: outcomeRaw)
            else { return .failed(.generic) }
            return .left(
                ConvoyLeaveResult(
                    outcome: outcome,
                    newLeaderUid: clean(data?["newLeaderUid"] as? String)
                )
            )
        }
        guard let convoy = parseItem(data?["convoy"]) else { return .failed(.generic) }
        return .updated(convoy)
    }

    static func parseInvite(_ data: [String: Any]?) -> ConvoyInviteMutationResult {
        guard parseItem(data?["convoy"]) != nil else { return .failed(.generic) }
        return .completed(
            ConvoyInviteResult(
                invitedCount: (data?["invited"] as? [Any] ?? []).count,
                skippedCount: (data?["skipped"] as? [Any] ?? []).count
            )
        )
    }

    private static func parseItems(_ raw: Any?) -> [ConvoyItem] {
        (raw as? [Any] ?? []).compactMap(parseItem)
    }

    private static func parseItem(_ raw: Any?) -> ConvoyItem? {
        guard let data = raw as? [String: Any],
              let convoyId = clean(data["convoyId"] as? String),
              let statusRaw = data["status"] as? String,
              let status = ConvoyStatus(rawValue: statusRaw)
        else { return nil }

        let members = (data["members"] as? [Any] ?? []).compactMap { raw -> ConvoyMember? in
            guard let member = raw as? [String: Any],
                  let uid = clean(member["uid"] as? String),
                  let roleRaw = member["role"] as? String,
                  let role = ConvoyRole(rawValue: roleRaw),
                  let inviteRaw = member["inviteStatus"] as? String,
                  let inviteStatus = ConvoyInviteStatus(rawValue: inviteRaw)
            else { return nil }
            return ConvoyMember(
                uid: uid,
                displayName: clean(member["displayName"] as? String),
                role: role,
                inviteStatus: inviteStatus
            )
        }
        let viewer: ConvoyViewer? = {
            guard let raw = data["viewer"] as? [String: Any],
                  let value = raw["inviteStatus"] as? String,
                  let status = ConvoyInviteStatus(rawValue: value)
            else { return nil }
            let role = (raw["role"] as? String).flatMap(ConvoyRole.init(rawValue:))
            return ConvoyViewer(inviteStatus: status, role: role)
        }()
        return ConvoyItem(
            convoyId: convoyId,
            title: clean(data["title"] as? String),
            status: status,
            members: members,
            viewer: viewer,
            createdAt: ChannelTime.parseIso(data["createdAt"] as? String)
        )
    }

    private static func clean(_ value: String?) -> String? {
        guard let value else { return nil }
        let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty ? nil : clean
    }
}

enum ConvoyManagementErrorMapper {
    static func mapList(_ code: KccFunctionsErrorCode) -> ConvoyActionError {
        switch code {
        case .unauthenticated: .signedOut
        case .permissionDenied: .notMember
        case .invalidArgument: .invalid
        default: .generic
        }
    }

    static func mapRespond(_ code: KccFunctionsErrorCode) -> ConvoyActionError {
        switch code {
        case .unauthenticated: .signedOut
        case .permissionDenied: .notMember
        case .invalidArgument: .invalid
        case .notFound: .notFound
        case .failedPrecondition: .unresolvedPrecondition
        default: .generic
        }
    }
    static func mapLifecycle(
        _ code: KccFunctionsErrorCode,
        action: ConvoyLifecycleAction
    ) -> ConvoyActionError {
        switch code {
        case .unauthenticated: .signedOut
        case .permissionDenied: action == .end ? .notLeader : .notMember
        case .invalidArgument: .invalid
        case .notFound: .notFound
        case .failedPrecondition:
            switch action {
            case .start: .cannotStart
            case .end: .alreadyEnded
            case .leave: .leaveFailed
            }
        default: .generic
        }
    }

    static func mapInvite(_ code: KccFunctionsErrorCode) -> ConvoyActionError {
        switch code {
        case .unauthenticated: .signedOut
        case .permissionDenied: .notMember
        case .invalidArgument: .invalid
        case .notFound: .notFound
        case .failedPrecondition: .noInvitees
        default: .generic
        }
    }
}

enum ConvoyManagementStrings {
    static func errorKey(_ error: ConvoyActionError) -> LocalizedStringKey {
        switch error {
        case .signedOut: "convoy.errorSignedOut"
        case .notMember: "convoy.errorNotMember"
        case .invalid: "convoy.errorInvalid"
        case .notFound: "convoy.errorNotFound"
        case .inviteGone: "convoy.errorInviteGone"
        case .alreadyInConvoy: "convoy.errorAlreadyInConvoy"
        case .unresolvedPrecondition, .generic: "convoy.errorGeneric"
        case .notLeader: "convoy.errorNotLeader"
        case .cannotStart: "convoy.errorCannotStart"
        case .alreadyEnded: "convoy.errorAlreadyEnded"
        case .leaveFailed: "convoy.errorLeaveFailed"
        case .noInvitees: "convoy.errorNoInvitees"
        }
    }
}
