import AuthenticationServices
import CryptoKit
import Foundation
import UIKit

/// Production ``AppleIDTokenProvider``: drives `ASAuthorizationController`
/// per docs/auth-mobile-requirements.md — random nonce, SHA-256 hash in the
/// request, raw nonce returned with the identity token for the Firebase
/// exchange.
///
/// NOTE: the Sign in with Apple capability requires a paid Apple Developer
/// team, so until the membership exists (ADR-002) this flow fails at runtime;
/// development uses ``EmulatorAppleIDTokenProvider`` against the Firebase
/// Auth emulator instead.
final class AppleSignInProvider: NSObject, AppleIDTokenProvider, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<AppleIDTokenPayload, Error>?
    private var rawNonce: String?

    func fetchAppleIDToken() async throws -> AppleIDTokenPayload {
        try await withCheckedThrowingContinuation { continuation in
            let nonce = Self.randomNonce()
            lock.lock()
            self.continuation = continuation
            self.rawNonce = nonce
            lock.unlock()

            let request = ASAuthorizationAppleIDProvider().createRequest()
            request.requestedScopes = [.fullName]
            request.nonce = Self.sha256(nonce)

            let controller = ASAuthorizationController(authorizationRequests: [request])
            controller.delegate = self
            controller.presentationContextProvider = self
            controller.performRequests()
        }
    }

    private func resume(with result: Result<AppleIDTokenPayload, Error>) {
        lock.lock()
        let continuation = self.continuation
        self.continuation = nil
        self.rawNonce = nil
        lock.unlock()
        switch result {
        case .success(let payload): continuation?.resume(returning: payload)
        case .failure(let error): continuation?.resume(throwing: error)
        }
    }

    /// A cryptographically random URL-safe nonce (docs snippet's contract).
    private static func randomNonce(length: Int = 32) -> String {
        var bytes = [UInt8](repeating: 0, count: length)
        let status = SecRandomCopyBytes(kSecRandomDefault, length, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        let charset = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._")
        return String(bytes.map { charset[Int($0) % charset.count] })
    }

    private static func sha256(_ input: String) -> String {
        SHA256.hash(data: Data(input.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}

extension AppleSignInProvider: ASAuthorizationControllerDelegate {
    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        guard
            let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
            let tokenData = credential.identityToken,
            let identityToken = String(data: tokenData, encoding: .utf8),
            let rawNonce
        else {
            resume(with: .failure(SignInUnavailableError()))
            return
        }
        resume(with: .success(AppleIDTokenPayload(
            identityToken: identityToken,
            rawNonce: rawNonce,
            fullName: credential.fullName
        )))
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        if let authError = error as? ASAuthorizationError, authError.code == .canceled {
            resume(with: .failure(SignInCancelledError()))
        } else {
            resume(with: .failure(error))
        }
    }
}

extension AppleSignInProvider: ASAuthorizationControllerPresentationContextProviding {
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        // The app is single-window; the first foreground window anchors the sheet.
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes
                .compactMap { ($0 as? UIWindowScene)?.keyWindow }
                .first ?? ASPresentationAnchor()
        }
    }
}

#if DEBUG
/// DEBUG-ONLY ``AppleIDTokenProvider`` for the Firebase Auth **emulator**,
/// which accepts unsigned identity tokens (it never verifies signatures).
/// Fabricates a structurally valid Apple ID token so the REAL repository
/// exchange path (`OAuthProvider.appleCredential` → `signIn`) is exercised
/// end-to-end without the Sign in with Apple entitlement or a paid Apple
/// membership. Active only when `FIREBASE_AUTH_EMULATOR_HOST` is set — never
/// in a production build (compiled out of Release entirely).
struct EmulatorAppleIDTokenProvider: AppleIDTokenProvider {
    let subject: String

    func fetchAppleIDToken() async throws -> AppleIDTokenPayload {
        let rawNonce = UUID().uuidString
        let header = ["alg": "none", "typ": "JWT"]
        let now = Int(Date().timeIntervalSince1970)
        let claims: [String: Any] = [
            "iss": "https://appleid.apple.com",
            "aud": "com.kungsbackacarcommunity.app",
            "sub": subject,
            "iat": now,
            "exp": now + 3600,
        ]
        let token = [try Self.base64URL(header), try Self.base64URL(claims), ""]
            .joined(separator: ".")
        return AppleIDTokenPayload(identityToken: token, rawNonce: rawNonce, fullName: nil)
    }

    private static func base64URL(_ object: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: object)
        return data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
#endif
