import Foundation

protocol AccountDeletionAuthorizing: Sendable {
    func assertCurrentSession() async throws
    func authorizeDeletion() async throws
}

/// A failed/uncertain backend response retries only the backend operation after
/// successful revocation. No Apple credential survives the authorization call.
actor AuthorizedAccountDeletionRepository: AccountDeletionRepository {
    let repository: any AccountDeletionRepository
    let authorization: any AccountDeletionAuthorizing
    private var authorized = false
    private var pending = false

    init(repository: any AccountDeletionRepository, authorization: any AccountDeletionAuthorizing) {
        self.repository = repository
        self.authorization = authorization
    }
    func deleteAccount(reason: String?) async throws {
        guard !pending else { throw CancellationError() }
        pending = true
        defer { pending = false }
        try await authorization.assertCurrentSession()
        if !authorized {
            do { try await authorization.authorizeDeletion() }
            catch is SignInCancelledError { throw CancellationError() }
            authorized = true
        }
        try Task.checkCancellation()
        try await authorization.assertCurrentSession()
        try await repository.deleteAccount(reason: reason)
    }
}
