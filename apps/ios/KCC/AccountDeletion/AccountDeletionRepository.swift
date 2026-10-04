import Foundation

/// The signed-in account-deletion boundary. Implementations invoke the
/// backend-owned two-stage deletion workflow; clients never delete user data
/// directly.
protocol AccountDeletionRepository: AnyObject, Sendable {
    func deleteAccount(reason: String?) async throws
}

/// User-facing failures derived only from stable callable codes. No backend
/// message, uid, reason text, or other sensitive value reaches presentation.
enum AccountDeletionFailure: Equatable, Sendable {
    /// The Firebase session is no longer valid. The user must sign in again
    /// before the signedIn callable can run.
    case authenticationRequired
    case invalidRequest
    case temporarilyUnavailable
    case notPermitted
    case generic

    static func from(_ error: Error) -> Self {
        guard let callable = error as? KccFunctionsError else { return .generic }
        switch callable.code {
        case .unauthenticated:
            return .authenticationRequired
        case .invalidArgument:
            return .invalidRequest
        case .unavailable, .resourceExhausted:
            return .temporarilyUnavailable
        case .permissionDenied, .failedPrecondition:
            return .notPermitted
        case .notFound, .internalError, .unknown:
            return .generic
        }
    }
}

enum AccountDeletionStatus: Equatable, Sendable {
    case idle
    case deleting
    case deleted
    case failed(AccountDeletionFailure)

    var isDeleting: Bool { self == .deleting }
}
