import FirebaseCore
import FirebaseFirestore
import Foundation

/// ``FriendPointsRepository`` backed by rules-gated `get()` reads of
/// `pointsLedger/{uid}` — the same public balance document the member
/// profile reads (iOS port of Android's `FirebaseFriendPointsRepository`).
///
/// The reads fan out one `get()` per uid (the rule grants `get`, never
/// `list`, so a single collection query would be denied), bounded to
/// ``maxConcurrentReads`` in-flight requests so a large friends list can't
/// burst into an unbounded read storm. Each read is best-effort: a missing
/// wallet or a failed read drops that uid from the result rather than
/// failing the batch, and the UI renders any absent uid as 0 — matching the
/// profile's own "degrade to 0" handling.
final class FirebaseFriendPointsRepository: FriendPointsRepository, @unchecked Sendable {
    private let firestore: Firestore

    private init(firestore: Firestore) {
        self.firestore = firestore
    }

    func balances(for uids: [String]) async -> [String: Int64] {
        var seen = Set<String>()
        let distinct = uids.filter { !$0.isEmpty && seen.insert($0).inserted }
        guard !distinct.isEmpty else { return [:] }
        let firestore = self.firestore
        return await withTaskGroup(of: (String, Int64)?.self) { group in
            var balances: [String: Int64] = [:]
            var next = distinct.makeIterator()
            var inFlight = 0
            // Sliding window: at most maxConcurrentReads reads in flight
            // (Android's Semaphore(MAX_CONCURRENT_READS) equivalent).
            func addNext() {
                guard let uid = next.next() else { return }
                inFlight += 1
                group.addTask {
                    await Self.balance(for: uid, in: firestore)
                }
            }
            for _ in 0..<Self.maxConcurrentReads { addNext() }
            while inFlight > 0 {
                guard let result = await group.next() else { break }
                inFlight -= 1
                if let (uid, balance) = result { balances[uid] = balance }
                addNext()
            }
            return balances
        }
    }

    /// One best-effort read; nil on a missing wallet, absent balance, or any
    /// read failure.
    private static func balance(for uid: String, in firestore: Firestore) async -> (String, Int64)? {
        do {
            let snapshot = try await firestore
                .collection(pointsLedger)
                .document(uid)
                .getDocument()
            guard let balance = snapshot.get("balance") as? NSNumber else { return nil }
            return (uid, balance.int64Value)
        } catch {
            return nil
        }
    }

    // MARK: - Factory

    private static let pointsLedger = "pointsLedger"

    /// Cap on concurrent `get()` reads so a large friends list can't burst.
    private static let maxConcurrentReads = 8

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseFriendPointsRepository?

    /// Returns the process-wide repository, or nil when Firebase is not
    /// configured — no repository is wired, the points overlay stays empty,
    /// and every friend simply renders as 0 rather than crashing.
    static func createIfAvailable() -> FriendPointsRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ),
            // Settings may only change before first use; another repository
            // (events, conversations) may already have pointed this shared
            // instance at the emulator — skip the redundant re-set then.
            firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseFriendPointsRepository(firestore: firestore)
        cached = repository
        return repository
    }
}
