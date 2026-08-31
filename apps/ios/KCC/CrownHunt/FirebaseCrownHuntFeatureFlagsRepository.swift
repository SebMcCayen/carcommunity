import FirebaseCore
import FirebaseFirestore
import Foundation

/// ``CrownHuntFeatureFlagsRepository`` backed by a one-shot read of
/// `config/featureFlags` (readable by any authenticated user — firestore.rules
/// `allow read: if isAuthenticated()`), folded onto the contract defaults.
///
/// The mirror of Android reading the flags on launch/resume: a present boolean
/// is taken as-is, and the document being absent, unreadable, or missing a
/// field degrades that flag to its contract default (never to "off"). The
/// backend stays authoritative. Construction is guarded (``createIfAvailable()``
/// returns nil without Firebase — the surface then uses the contract defaults,
/// so the shop stays dark exactly as it would when the flag reads false).
final class FirebaseCrownHuntFeatureFlagsRepository: CrownHuntFeatureFlagsRepository, @unchecked Sendable {
    private let firestore: Firestore

    private init(firestore: Firestore) {
        self.firestore = firestore
    }

    func flags() async -> CrownHuntFlags {
        do {
            let document = try await firestore
                .collection(Self.config).document(Self.featureFlags).getDocument()
            return CrownHuntFlags.resolve(from: document.data())
        } catch {
            // A failed read is not "off": every flag degrades to its documented
            // default, so a transient error can never silently switch the shop on.
            return .contractDefaults
        }
    }

    private static let config = "config"
    private static let featureFlags = "featureFlags"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseCrownHuntFeatureFlagsRepository?

    /// A flags repository, or nil in a config-less/CI build (no Firebase).
    static func createIfAvailable() -> CrownHuntFeatureFlagsRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let repository = FirebaseCrownHuntFeatureFlagsRepository(firestore: CrownHuntFirebase.firestore())
        cached = repository
        return repository
    }
}
