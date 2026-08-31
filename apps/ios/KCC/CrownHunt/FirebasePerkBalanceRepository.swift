import FirebaseCore
import FirebaseFirestore
import Foundation

/// ``PerkBalanceRepository`` backed by a listener on `pointsLedger/{uid}.balance`
/// — the iOS port of the balance half of Android's
/// `FirebasePointsRepository.observeBalance`.
///
/// A single-document read by id — NOT owner-scoped: firestore.rules grants
/// `get` on `pointsLedger/{uid}` to any authenticated user (only the
/// append-only `/entries` subcollection underneath it stays owner-only).
/// Keeps the last-known balance on a transient error rather than emitting nil
/// (which would misrender as 0 CP). Construction is guarded
/// (``createIfAvailable()`` returns nil without Firebase).
final class FirebasePerkBalanceRepository: PerkBalanceRepository, @unchecked Sendable {
    private let firestore: Firestore

    private init(firestore: Firestore) {
        self.firestore = firestore
    }

    func balance(uid: String) -> AsyncStream<Int?> {
        let document = firestore.collection(Self.ledger).document(uid)
        return AsyncStream { continuation in
            let registration = document.addSnapshotListener { snapshot, error in
                // Keep the last known balance on a transient error rather than
                // emitting nil (which would misrender as 0) — Android's guard.
                if error != nil { return }
                continuation.yield((snapshot?.get(Self.balanceField) as? NSNumber)?.intValue)
            }
            let box = CrownHuntListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    private static let ledger = "pointsLedger"
    private static let balanceField = "balance"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebasePerkBalanceRepository?

    /// A balance repository, or nil in a config-less/CI build (no Firebase) —
    /// the shop then simply shows 0 CP and no affordability hint.
    static func createIfAvailable() -> PerkBalanceRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let repository = FirebasePerkBalanceRepository(firestore: CrownHuntFirebase.firestore())
        cached = repository
        return repository
    }
}
