import Foundation

/// The canonical callable error vocabulary (contracts/errors/errors.json),
/// restricted to the codes the events slice branches on plus the generic
/// transport/backend codes — the iOS analog of Android's code-string mapping
/// helpers (`Events.createFailureFromCode` / `manageFailureFromCode`).
///
/// Clients branch on the CODE, never on the human-readable message
/// (docs/api-guidelines.md, "Stable error codes"). The enum is pure Swift
/// (no Firebase import) so the mapping is unit-testable with plain strings;
/// ``KccFunctionsClient`` translates the Firebase SDK's error into this
/// vocabulary at the seam.
enum KccFunctionsErrorCode: String, Equatable, Sendable, CaseIterable {
    /// No valid Firebase session.
    case unauthenticated = "unauthenticated"
    /// Authenticated but not authorized (e.g. not the event's creator).
    case permissionDenied = "permission-denied"
    /// Input validation failed.
    case invalidArgument = "invalid-argument"
    /// Resource does not exist (e.g. a draft/cancelled event's roster).
    case notFound = "not-found"
    /// Rate limit or quota exceeded (the events.create 3-per-24h cap).
    case resourceExhausted = "resource-exhausted"
    /// State precondition not met (e.g. editing a cancelled event).
    case failedPrecondition = "failed-precondition"
    /// Unexpected server error.
    case internalError = "internal"
    /// Temporary unavailability (offline, backend hiccup) — retryable.
    case unavailable = "unavailable"
    /// Anything outside the contract vocabulary (SDK-local failures,
    /// cancellation, deadline, malformed responses). Not a contract code;
    /// the catch-all so callers always have something to branch on.
    case unknown = "unknown"

    /// Maps a wire/SDK error-code spelling onto the contract vocabulary.
    ///
    /// Accepts both the `HttpsError`/wire spelling (`permission-denied`) and
    /// an SDK enum name spelling (`PERMISSION_DENIED`), case-insensitively —
    /// the same tolerance Android's `createFailureFromCode` /
    /// `manageFailureFromCode` apply — so the mapping is pinned by unit tests
    /// without a Firebase dependency. An absent or unrecognized code is
    /// ``unknown``, never a fabricated contract code.
    static func fromWire(_ code: String?) -> KccFunctionsErrorCode {
        guard let code else { return .unknown }
        let normalized = code
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "_", with: "-")
        return KccFunctionsErrorCode(rawValue: normalized) ?? .unknown
    }
}

/// A callable failure carrying the contract code and an allowlisted reason.
///
/// PII-SAFE BY CONSTRUCTION: backend messages (which can embed request
/// payloads, uids, or project details) are never carried. Only known
/// machine-readable reasons are accepted from error details —
/// the same rule Android's `firestoreCode()` and the auth slice's
/// `SignInFailureDetails` follow.
struct KccFunctionsError: Error, Equatable, Sendable {
    /// The contract error code the caller branches on.
    let code: KccFunctionsErrorCode
    /// Allowlisted machine reason; never a backend message or payload.
    let reason: KccFunctionsFailureReason?

    init(code: KccFunctionsErrorCode, reason: KccFunctionsFailureReason? = nil) {
        self.code = code
        self.reason = reason
    }
}

enum KccFunctionsFailureReason: String, Equatable, Sendable {
    case noValidConvoyInvitees = "no_valid_convoy_invitees"
    case importedIncident = "imported_incident"
    case incidentInactive = "incident_inactive"
    case outOfRange = "out_of_range"
    case positionTooOld = "position_too_old"
    case voteNotCounted = "vote_not_counted"

    static func fromDetails(_ raw: Any?) -> Self? {
        guard let details = raw as? [String: Any],
              let reason = details["reason"] as? String
        else { return nil }
        return Self(rawValue: reason)
    }
}
