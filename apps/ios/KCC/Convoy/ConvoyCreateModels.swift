import Foundation
import SwiftUI

enum ConvoyCreateError: Equatable, Sendable {
    case signedOut
    case notMember
    case invalid
    case noInvitees
    case alreadyInConvoy
    case membershipUncertain
    /// The callable overloads failed-precondition; the coordinator resolves it
    /// with a fresh list before selecting a user-facing reason.
    case unresolvedPrecondition
    case generic
}

enum ConvoyCreateErrorMapper {
    static func mapCreate(_ code: KccFunctionsErrorCode) -> ConvoyCreateError {
        switch code {
        case .unauthenticated: .signedOut
        case .permissionDenied: .notMember
        case .invalidArgument: .invalid
        case .failedPrecondition: .unresolvedPrecondition
        default: .generic
        }
    }

    static func mapList(_ code: KccFunctionsErrorCode) -> ConvoyCreateError {
        switch code {
        case .unauthenticated: .signedOut
        case .permissionDenied: .notMember
        default: .generic
        }
    }
}

enum ConvoyCreateStrings {
    static func errorKey(_ error: ConvoyCreateError) -> LocalizedStringKey {
        switch error {
        case .signedOut: "convoy.errorSignedOut"
        case .notMember: "convoy.errorNotMember"
        case .invalid: "convoy.errorInvalid"
        case .noInvitees: "convoy.errorNoInvitees"
        case .alreadyInConvoy: "convoy.errorAlreadyInConvoy"
        case .membershipUncertain: "convoy.membershipUncertainHint"
        case .unresolvedPrecondition, .generic: "convoy.errorGeneric"
        }
    }
}

struct ConvoyCreateSnapshot: Equatable, Sendable {
    let hasActiveConvoy: Bool
    /// `convoy-list` returns at most 200 rows. Fewer rows proves the active
    /// membership scan was complete; exactly 200 cannot rule out an older row.
    let isExhaustive: Bool
}

enum ConvoyCreateListResult: Equatable, Sendable {
    case loaded(ConvoyCreateSnapshot)
    case failed(ConvoyCreateError)
}

struct ConvoyCreated: Equatable, Sendable {
    let convoyId: String
    let invited: [String]
    let skippedCount: Int
    let convoy: ConvoyItem?

    init(convoyId: String, invited: [String], skippedCount: Int, convoy: ConvoyItem? = nil) {
        self.convoyId = convoyId
        self.invited = invited
        self.skippedCount = skippedCount
        self.convoy = convoy
    }
}

enum ConvoyCreateResult: Equatable, Sendable {
    case created(ConvoyCreated)
    case failed(ConvoyCreateError)
}

enum ConvoyCreateResponseParser {
    static let listLimit = 200

    static func parseList(_ data: [String: Any]?) -> ConvoyCreateSnapshot {
        let rawConvoys = data?["convoys"] as? [Any]
        let convoys = rawConvoys ?? []
        let hasActive = convoys.contains { raw in
            guard let convoy = raw as? [String: Any],
                  let status = convoy["status"] as? String,
                  status == "forming" || status == "active",
                  let viewer = convoy["viewer"] as? [String: Any]
            else { return false }
            return (viewer["inviteStatus"] as? String) == "accepted"
        }
        return ConvoyCreateSnapshot(
            hasActiveConvoy: hasActive,
            isExhaustive: rawConvoys != nil && convoys.allSatisfy { raw in
                guard let row = raw as? [String: Any],
                      (row["convoyId"] as? String)?.trimmedNonBlank != nil,
                      let status = row["status"] as? String,
                      let viewer = row["viewer"] as? [String: Any],
                      let inviteStatus = viewer["inviteStatus"] as? String
                else { return false }
                return ["forming", "active", "ended"].contains(status)
                    && ["invited", "accepted", "declined"].contains(inviteStatus)
            } && ((data?["isExhaustive"] as? Bool) ?? (convoys.count < listLimit))
        )
    }

    static func parseCreate(_ data: [String: Any]?) -> ConvoyCreateResult {
        guard let convoy = data?["convoy"] as? [String: Any],
              let convoyId = (convoy["convoyId"] as? String)?.trimmedNonBlank
        else { return .failed(.generic) }

        let invited = (data?["invited"] as? [Any] ?? []).compactMap {
            ($0 as? String)?.trimmedNonBlank
        }
        let skippedCount = (data?["skipped"] as? [Any] ?? []).count
        return .created(
            ConvoyCreated(
                convoyId: convoyId,
                invited: invited,
                skippedCount: skippedCount,
                convoy: ConvoyManagementParser.parseItem(convoy)
            )
        )
    }
}

extension String {
    fileprivate var trimmedNonBlank: String? {
        let value = trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}
