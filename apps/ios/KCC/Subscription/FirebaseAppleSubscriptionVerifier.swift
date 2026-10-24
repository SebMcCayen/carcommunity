import FirebaseAuth
import Foundation

final class FirebaseAppleSubscriptionVerifier: AppleSubscriptionVerifying, @unchecked Sendable {
    private let functions: KccFunctionsClient
    private let auth: Auth

    private init(functions: KccFunctionsClient, auth: Auth) {
        self.functions = functions
        self.auth = auth
    }

    func verify(signedTransaction: String, expectedUID: String) async throws -> SubscriptionVerificationResult {
        guard auth.currentUser?.uid == expectedUID else {
            throw AppleSubscriptionVerificationError.signedOut
        }
        let data = try await functions.call("subscription-verify", payload: [
            "platform": "apple",
            "purchaseToken": signedTransaction,
        ])
        guard let response = data as? [String: Any],
            let entitlement = response["entitlement"] as? String,
            let status = response["status"] as? String,
            let rawTier = response["tier"] as? String,
            let tier = SubscriptionTier(rawValue: rawTier)
        else { throw AppleSubscriptionVerificationError.malformedResponse }

        // applyEntitlement may grant or revoke the activeMember claim. Force a
        // fresh ID token before the UI exposes the new authorization state.
        guard let user = auth.currentUser, user.uid == expectedUID else {
            throw AppleSubscriptionVerificationError.signedOut
        }
        try await refreshIDToken(for: user)
        guard auth.currentUser?.uid == expectedUID else {
            throw AppleSubscriptionVerificationError.signedOut
        }
        return SubscriptionVerificationResult(
            entitlement: entitlement,
            status: status,
            tier: tier
        )
    }

    static func createIfAvailable() -> AppleSubscriptionVerifying? {
        guard let functions = KccFunctionsClient.createIfAvailable() else { return nil }
        return FirebaseAppleSubscriptionVerifier(functions: functions, auth: Auth.auth())
    }

    private func refreshIDToken(for user: User) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            user.getIDTokenForcingRefresh(true) { _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }
}

private enum AppleSubscriptionVerificationError: Error {
    case malformedResponse
    case signedOut
}
