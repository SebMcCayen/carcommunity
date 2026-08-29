import FirebaseCore
import FirebaseFunctions
import Foundation

/// The app's single seam onto Cloud Functions callables — the iOS analog of
/// Android's `FirebaseFunctions.getInstance("europe-west1")` usage in
/// `FirebaseEventsRepository.kt`.
///
/// Every KCC callable is deployed to `europe-west1`
/// (functions/src — grouped exports like `events-checkIn`), so the region is
/// pinned here once; a repository that needs a callable takes this client
/// rather than talking to the SDK directly, and the thrown error is always a
/// ``KccFunctionsError`` carrying only a contract error code
/// (contracts/errors/errors.json) — never the SDK message.
///
/// Construction is guarded like ``FirebaseBootstrap``'s other consumers:
/// ``createIfAvailable()`` returns nil when Firebase is not configured in
/// this build (no GoogleService-Info.plist), so config-less builds and CI
/// never touch the SDK.
final class KccFunctionsClient: @unchecked Sendable {
    /// Every KCC callable's deployment region. Android mirror:
    /// `FirebaseEventsRepository.REGION`.
    static let region = "europe-west1"

    private let functions: Functions

    private init(functions: Functions) {
        self.functions = functions
    }

    /// Invokes the callable `name` (grouped-export spelling, e.g.
    /// `events-checkIn`) with `payload` and returns the raw response data.
    ///
    /// - Throws: ``KccFunctionsError`` with the contract code — the SDK error
    ///   is translated at this seam and never propagates, so callers stay
    ///   Firebase-free and PII-safe.
    func call(_ name: String, payload: [String: Any]) async throws -> Any? {
        do {
            let result = try await functions.httpsCallable(name).call(payload)
            return result.data
        } catch {
            throw KccFunctionsError(code: Self.contractCode(from: error))
        }
    }

    /// Translates an SDK callable failure into the contract vocabulary.
    /// Codes outside the contract (cancelled, deadline, data-loss, …) fold to
    /// ``KccFunctionsErrorCode/unknown`` rather than inventing a contract code.
    static func contractCode(from error: Error) -> KccFunctionsErrorCode {
        let nsError = error as NSError
        guard nsError.domain == FunctionsErrorDomain,
            let code = FunctionsErrorCode(rawValue: nsError.code)
        else { return .unknown }
        switch code {
        case .unauthenticated: return .unauthenticated
        case .permissionDenied: return .permissionDenied
        case .invalidArgument: return .invalidArgument
        case .notFound: return .notFound
        case .resourceExhausted: return .resourceExhausted
        case .failedPrecondition: return .failedPrecondition
        case .internal: return .internalError
        case .unavailable: return .unavailable
        default: return .unknown
        }
    }

    // MARK: - Factory

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: KccFunctionsClient?

    /// Returns the process-wide client when Firebase is configured for this
    /// build, or nil when GoogleService-Info.plist is absent (CI, local
    /// validation builds — see apps/ios/README.md).
    ///
    /// When the `FIREBASE_FUNCTIONS_EMULATOR_HOST` environment variable is
    /// set (e.g. `127.0.0.1:5001`, matching firebase.json's functions
    /// emulator port), the Functions SDK is pointed at the emulator before
    /// first use — the same seam ``FirebaseAuthRepository`` provides via
    /// `FIREBASE_AUTH_EMULATOR_HOST`.
    static func createIfAvailable() -> KccFunctionsClient? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let functions = Functions.functions(region: region)
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FUNCTIONS_EMULATOR_HOST"]
        ) {
            functions.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let client = KccFunctionsClient(functions: functions)
        cached = client
        return client
    }
}

/// Shared `host:port` parsing for the `FIREBASE_*_EMULATOR_HOST` environment
/// variables (auth 9099, functions 5001, firestore 8080 — firebase.json).
enum FirebaseEmulatorHost {
    /// Parses `"127.0.0.1:5001"` into its parts; nil for an absent or
    /// malformed value — an empty host (`":5001"`) or an out-of-range port —
    /// so a misconfigured variable degrades to production behaviour instead
    /// of pointing the SDK at a nonsense address.
    static func parse(_ value: String?) -> (host: String, port: Int)? {
        guard let value else { return nil }
        let parts = value.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: ":")
        guard parts.count == 2,
            !parts[0].isEmpty,
            let port = Int(parts[1]),
            (1...65535).contains(port)
        else { return nil }
        return (String(parts[0]), port)
    }
}
