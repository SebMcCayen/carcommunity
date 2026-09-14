import Foundation
import SwiftUI

enum ConvoyCreateError: Equatable, Sendable {
    case signedOut
    case notMember
    case invalid
    case noInvitees
    case alreadyInConvoy
    case generic
}

enum ConvoyCreateErrorMapper {
    static func mapCreate(_ code: KccFunctionsErrorCode) -> ConvoyCreateError {
        switch code {
        case .unauthenticated: .signedOut
        case .permissionDenied: .notMember
        case .invalidArgument: .invalid
        case .failedPrecondition: .noInvitees
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
        case .generic: "convoy.errorGeneric"
        }
    }
}

struct ConvoyCreateSnapshot: Equatable, Sendable {
    let hasActiveConvoy: Bool
}

enum ConvoyCreateListResult: Equatable, Sendable {
    case loaded(ConvoyCreateSnapshot)
    case failed(ConvoyCreateError)
}

struct ConvoyCreated: Equatable, Sendable {
    let convoyId: String
    let invited: [String]
    let skippedCount: Int
}

enum ConvoyCreateResult: Equatable, Sendable {
    case created(ConvoyCreated)
    case failed(ConvoyCreateError)
}

enum ConvoyCreateResponseParser {
    static func parseList(_ data: [String: Any]?) -> ConvoyCreateSnapshot {
        let convoys = data?["convoys"] as? [Any] ?? []
        let hasActive = convoys.contains { raw in
            guard let convoy = raw as? [String: Any],
                  let status = convoy["status"] as? String,
                  status == "forming" || status == "active",
                  let viewer = convoy["viewer"] as? [String: Any]
            else { return false }
            return (viewer["inviteStatus"] as? String) == "accepted"
        }
        return ConvoyCreateSnapshot(hasActiveConvoy: hasActive)
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
            ConvoyCreated(convoyId: convoyId, invited: invited, skippedCount: skippedCount)
        )
    }
}

extension String {
    fileprivate var trimmedNonBlank: String? {
        let value = trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}
