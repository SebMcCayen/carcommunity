import Foundation

/// One settled emission of the member's claim-history read — the newest-first
/// list of their own claim attempts, or a failure. Settled-only, like every
/// other repository stream in this codebase.
enum CrownClaimsSnapshot: Equatable, Sendable {
    /// The read failed. `code` is the bare Firestore status name when one was
    /// available (PII-safe), never the exception text.
    case failed(code: String?)
    /// The member's own claim attempts, newest first.
    case loaded([CrownHuntClaim])
}

/// Read-only access to the member's own Crown-Hunt claim history
/// (`crownHuntClaims`, owner-only read — firestore.rules
/// `resource.data.userId == request.auth.uid`).
///
/// There is no Android counterpart repository — the Android hub does not
/// surface claim history — but the owner read is rules-permitted and the
/// contract shape (`crownHuntClaim`) is shared, so the iOS list reads the
/// contract directly. Firebase-free protocol so the coordinator + screen are
/// unit-testable with a fake; the one implementation
/// (``FirebaseCrownHuntClaimsRepository``) is a bounded, rules-gated Firestore
/// query. Nothing here writes — every claim is written by `crownHunt.submitClaim`.
protocol CrownHuntClaimsRepository: AnyObject, Sendable {
    /// A one-shot read per subscription of the member's own claims, ordered
    /// newest first and bounded to ``CrownHuntClaims/queryLimit``. Each call
    /// returns a fresh stream that emits exactly one settled value, then
    /// finishes.
    func claims(uid: String) -> AsyncStream<CrownClaimsSnapshot>
}

/// Query-shaping constants for the claim-history read.
enum CrownHuntClaims {
    /// The claim-history read is bounded (newest first by `createdAt`) so a
    /// long-lived account's ever-growing `crownHuntClaims` can never turn into
    /// an unbounded read — mirroring the bounded reads elsewhere in the app
    /// (the events list, the active-points listener).
    static let queryLimit = 100
}
