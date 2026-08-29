import Foundation
import Observation

/// UI-facing progress of an explicit sign-in attempt — the iOS port of
/// Android's `SignInStatus`.
enum SignInStatus: Equatable, Sendable {
    case idle
    case inProgress
    case failed(SignInFailure)
}

enum SignInFailure: Equatable, Sendable {
    /// Sign-in cannot run in this build (no Firebase config / no entitlement).
    case unavailable

    /// The Apple flow or Firebase exchange failed.
    ///
    /// Android additionally has `NO_GOOGLE_ACCOUNT` (a user-fixable missing
    /// device account). Apple sign-in has no equivalent state — the Apple ID
    /// sheet handles account setup itself — so it is intentionally not ported.
    case generic
}

/// Which stage of the sign-in flow produced a failure — reported for
/// observability. Wire names match Android's `SignInStep` so backend
/// diagnostics bucket both platforms together.
enum SignInStep: String, Sendable {
    /// Fetching the Apple identity token via the authorization flow.
    case credentialFetch = "credential_fetch"

    /// Exchanging the Apple identity token for a Firebase session.
    case firebaseExchange = "firebase_exchange"
}

/// PII-SAFE description of a sign-in failure — the iOS port of Android's
/// `SignInFailureDetails`. Everything here is safe to ship off-device: type
/// names and stable error-code constants ONLY — never the error's message,
/// credentials, tokens, email, or any PII.
struct SignInFailureDetails: Equatable, Sendable {
    /// The failing error's Swift type name (e.g. `SignInFailedError`).
    let errorType: String
    /// Which stage failed.
    let step: SignInStep
    /// An optional stable code carried from the throw site (a Firebase
    /// `AuthErrorCode` name via ``SignInDiagnosticInfo``); nil when none.
    let statusCode: String?
}

/// Fire-and-forget sink for a sanitized sign-in failure. Implementations must
/// never throw and must not block.
protocol SignInFailureReporter: Sendable {
    func reportSignInFailure(_ details: SignInFailureDetails)
}

/// No-op reporter (default): keeps ``SignInCoordinator`` pure and
/// Firebase-free.
struct NoopSignInFailureReporter: SignInFailureReporter {
    func reportSignInFailure(_ details: SignInFailureDetails) {}
}

/// Orchestrates Sign in with Apple: fetch an Apple identity token, exchange
/// it for a Firebase session. Pure Swift (no Firebase/UIKit types) so the
/// flow is unit-testable with fakes — the iOS port of Android's
/// `SignInCoordinator`.
@MainActor
@Observable
final class SignInCoordinator {
    private let tokenProvider: AppleIDTokenProvider
    private let repository: AuthRepository
    private let failureReporter: SignInFailureReporter

    private(set) var status: SignInStatus = .idle

    init(
        tokenProvider: AppleIDTokenProvider,
        repository: AuthRepository,
        failureReporter: SignInFailureReporter = NoopSignInFailureReporter()
    ) {
        self.tokenProvider = tokenProvider
        self.repository = repository
        self.failureReporter = failureReporter
    }

    /// Runs one sign-in attempt. Re-entrant calls while in progress are
    /// ignored.
    func signIn() async {
        if status == .inProgress { return }
        status = .inProgress

        // Step 1 — fetch the Apple identity token. Split from the exchange so
        // a failure can be attributed to the exact stage for diagnostics.
        let payload: AppleIDTokenPayload
        do {
            payload = try await tokenProvider.fetchAppleIDToken()
        } catch is CancellationError {
            status = .idle
            return
        } catch is SignInCancelledError {
            // The USER dismissed the Apple ID sheet. Not a fault, so it is
            // dropped HERE — before any diagnostics are produced (the same
            // rule as Android; see its issue #457). Idle, not failed: the
            // user chose to back out, so the login screen returns to rest
            // instead of accusing them of an error.
            status = .idle
            return
        } catch is SignInUnavailableError {
            // Configuration gap, not a runtime error — never reported.
            status = .failed(.unavailable)
            return
        } catch {
            report(error, step: .credentialFetch)
            status = .failed(.generic)
            return
        }

        // Step 2 — exchange the token for a Firebase session.
        do {
            try await repository.signIn(with: payload)
            status = .idle
        } catch is CancellationError {
            status = .idle
        } catch is SignInUnavailableError {
            // Configuration gap surfaced at the exchange — same handling as
            // step 1: never reported as a failure.
            status = .failed(.unavailable)
        } catch {
            report(error, step: .firebaseExchange)
            status = .failed(.generic)
        }
    }

    /// Clears a failure state, e.g. when the user dismisses the error.
    func resetFailure() {
        if case .failed = status {
            status = .idle
        }
    }

    /// Builds the sanitized failure details and hands them to the reporter.
    /// Error messages (which may reference credentials) are NEVER logged or
    /// included — only the type name, the failing step, and any stable code
    /// carried via ``SignInDiagnosticInfo``. Guarded so a reporting fault
    /// can't mask the sign-in failure.
    private func report(_ error: Error, step: SignInStep) {
        failureReporter.reportSignInFailure(Self.describeFailure(error, step: step))
    }

    /// Derives the PII-safe details from an error: its Swift type name and
    /// any ``SignInDiagnosticInfo/diagnosticCode``. Pure — inspects only type
    /// names and our own carrier protocol, never error messages.
    nonisolated static func describeFailure(_ error: Error, step: SignInStep) -> SignInFailureDetails {
        SignInFailureDetails(
            errorType: String(describing: type(of: error)),
            step: step,
            statusCode: (error as? SignInDiagnosticInfo)?.diagnosticCode
        )
    }
}
