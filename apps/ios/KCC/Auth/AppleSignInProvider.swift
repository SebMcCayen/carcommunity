import AuthenticationServices
import CryptoKit
import Foundation
import UIKit

/// The system RNG could not produce a sign-in nonce. Extremely rare; thrown
/// (rather than crashing) so the attempt fails gracefully as a generic,
/// reportable failure. PII-safe: carries nothing but its type name.
struct NonceGenerationError: Error {}

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
    /// Strong reference to the in-flight controller: its `delegate` is weak
    /// and nothing documented keeps a local alive until the callbacks fire,
    /// so without this the request could die with the continuation never
    /// resumed. Cleared in ``resume(with:)``.
    private var controller: ASAuthorizationController?
    /// Set when the calling task is cancelled before the continuation is
    /// stored, so the store step resumes immediately instead of hanging.
    private var cancelledBeforeStart = false

    func fetchAppleIDToken() async throws -> AppleIDTokenPayload {
        resetCancellationFlag()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let nonce: String
                do {
                    nonce = try Self.randomNonce()
                } catch {
                    // RNG failure: fail this attempt gracefully (reported as
                    // a generic failure) instead of crashing the app.
                    continuation.resume(throwing: error)
                    return
                }

                let request = ASAuthorizationAppleIDProvider().createRequest()
                request.requestedScopes = [.fullName]
                request.nonce = Self.sha256(nonce)

                let controller = ASAuthorizationController(authorizationRequests: [request])
                controller.delegate = self
                controller.presentationContextProvider = self

                lock.lock()
                if cancelledBeforeStart {
                    cancelledBeforeStart = false
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                self.continuation = continuation
                self.rawNonce = nonce
                self.controller = controller
                lock.unlock()

                controller.performRequests()
            }
        } onCancel: {
            cancelWaiting()
        }
    }

    /// Resolves a pending flow with `CancellationError` when the calling
    /// task is cancelled: the caller returns immediately (the coordinator
    /// maps it back to idle) and the stored state is cleared so the next
    /// attempt starts clean. The Apple sheet, if already up, is left for the
    /// user to dismiss. When cancellation lands before the continuation is
    /// stored, a flag makes the store step resume instead.
    /// Clears any stale ``cancelledBeforeStart`` from a previous attempt.
    /// Synchronous on purpose: `NSLock` may not be taken directly inside an
    /// async function.
    private func resetCancellationFlag() {
        lock.lock()
        cancelledBeforeStart = false
        lock.unlock()
    }

    private func cancelWaiting() {
        lock.lock()
        guard continuation != nil else {
            cancelledBeforeStart = true
            lock.unlock()
            return
        }
        lock.unlock()
        resume(with: .failure(CancellationError()))
    }

    private func resume(with result: Result<AppleIDTokenPayload, Error>) {
        lock.lock()
        let continuation = self.continuation
        self.continuation = nil
        self.rawNonce = nil
        self.controller = nil
        lock.unlock()
        switch result {
        case .success(let payload): continuation?.resume(returning: payload)
        case .failure(let error): continuation?.resume(throwing: error)
        }
    }

    /// A cryptographically random URL-safe nonce (docs snippet's contract).
    ///
    /// - Throws: ``NonceGenerationError`` when the system RNG fails, so the
    ///   attempt surfaces as a normal sign-in failure instead of crashing.
    private static func randomNonce(length: Int = 32) throws -> String {
        var bytes = [UInt8](repeating: 0, count: length)
        let status = SecRandomCopyBytes(kSecRandomDefault, length, &bytes)
        guard status == errSecSuccess else { throw NonceGenerationError() }
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
