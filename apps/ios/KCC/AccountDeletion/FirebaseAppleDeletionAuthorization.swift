import FirebaseAuth
import Foundation

/// Bound to the exact Firebase User object captured when the deletion route is
/// built. Reauthentication never signs into or links a different account.
final class FirebaseAppleDeletionAuthorization: AccountDeletionAuthorizing, @unchecked Sendable {
    private let auth: Auth
    private let user: User
    private let provider: any AppleIDTokenProvider
    init(auth: Auth, user: User, provider: any AppleIDTokenProvider = AppleSignInProvider()) {
        self.auth = auth
        self.user = user
        self.provider = provider
    }
    func assertCurrentSession() async throws {
        try Task.checkCancellation()
        guard auth.currentUser === user else { throw CancellationError() }
    }
    func authorizeDeletion() async throws {
        try await assertCurrentSession()
        guard let apple = user.providerData.first(where: { $0.providerID == "apple.com" }) else { return }
        let payload = try await provider.fetchAppleIDToken()
        try await assertCurrentSession()
        guard payload.providerUserID == apple.uid,
              let code = payload.authorizationCode, !code.isEmpty else {
            throw KccFunctionsError(code: .permissionDenied)
        }
        let credential = OAuthProvider.appleCredential(withIDToken: payload.identityToken,
            rawNonce: payload.rawNonce, fullName: payload.fullName)
        _ = try await user.reauthenticate(with: credential)
        try await assertCurrentSession()
        try await auth.revokeToken(withAuthorizationCode: code)
        try await assertCurrentSession()
    }
}
