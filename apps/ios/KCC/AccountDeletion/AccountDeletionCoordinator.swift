import Foundation
import Observation

/// Serializes the irreversible request and exposes a small, PII-free state
/// machine to SwiftUI. A successful call is terminal: the route signs the
/// user out immediately, while the backend continues the retention lifecycle.
@MainActor
@Observable
final class AccountDeletionCoordinator {
    private let repository: AccountDeletionRepository?
    private(set) var status: AccountDeletionStatus = .idle

    init(repository: AccountDeletionRepository?) {
        self.repository = repository
    }

    var isAvailable: Bool { repository != nil }

    /// Returns true only after the backend acknowledges a pending deletion.
    /// Re-entrant taps are ignored, and cancellation returns to idle while
    /// remaining cancellation to the caller.
    @discardableResult
    func delete(reason: String? = nil) async throws -> Bool {
        guard status != .deleting, status != .deleted, let repository else { return false }
        status = .deleting
        do {
            try await repository.deleteAccount(reason: reason)
            guard !Task.isCancelled else { throw CancellationError() }
            status = .deleted
            return true
        } catch is CancellationError {
            status = .idle
            throw CancellationError()
        } catch {
            status = .failed(AccountDeletionFailure.from(error))
            return false
        }
    }

    func resetFailure() {
        if case .failed = status { status = .idle }
    }
}
