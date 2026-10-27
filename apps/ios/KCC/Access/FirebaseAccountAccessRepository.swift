import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation

/// Own server-owned metadata drives presentation; backend rules remain authority.
final class FirebaseAccountAccessRepository: AccountAccessRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let auth: Auth
    private let functions: KccFunctionsClient
    private let observationsLock = NSLock()
    private var observations: [UUID: ProductAccessObservation] = [:]

    init(firestore: Firestore, auth: Auth, functions: KccFunctionsClient) {
        self.firestore = firestore; self.auth = auth; self.functions = functions
    }

    func updates(uid: String) -> AsyncStream<AccountAccessSnapshot> {
        AsyncStream { continuation in
            let id = UUID()
            let observation = ProductAccessObservation(uid: uid, auth: auth,
                functions: functions, continuation: continuation)
            observationsLock.withLock { observations[id] = observation }
            observation.start(firestore: firestore)
            continuation.onTermination = { [weak self] _ in
                observation.cancel()
                _ = self?.observationsLock.withLock { self?.observations.removeValue(forKey: id) }
            }
        }
    }

    func refreshProductAccess(uid: String) {
        let matching = observationsLock.withLock { observations.values.filter { $0.uid == uid } }
        for observation in matching { observation.refresh() }
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseAccountAccessRepository?
    static func createIfAvailable() -> AccountAccessRepository? {
        guard FirebaseApp.app() != nil, let functions = KccFunctionsClient.createIfAvailable() else { return nil }
        return lock.withLock {
            if let cached { return cached }
            let firestore = Firestore.firestore()
            if let emulator = FirebaseEmulatorHost.parse(ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]),
               firestore.settings.host != "\(emulator.host):\(emulator.port)" {
                firestore.useEmulator(withHost: emulator.host, port: emulator.port)
            }
            let repository = FirebaseAccountAccessRepository(firestore: firestore, auth: Auth.auth(), functions: functions)
            cached = repository
            return repository
        }
    }
}

private final class ProductAccessObservation: @unchecked Sendable {
    let uid: String
    private let auth: Auth
    private let functions: KccFunctionsClient
    private let continuation: AsyncStream<AccountAccessSnapshot>.Continuation
    private let lock = NSLock()
    private var profile: AccountAccess?
    private var product: [String: Any]?
    private var last: AccountAccess?
    private var registrations: [ListenerRegistration] = []
    private var request: Task<Void, Never>?
    private var cancelled = false
    private var refreshing = false

    init(uid: String, auth: Auth, functions: KccFunctionsClient,
         continuation: AsyncStream<AccountAccessSnapshot>.Continuation) {
        self.uid = uid; self.auth = auth; self.functions = functions; self.continuation = continuation
    }
    func start(firestore: Firestore) {
        let user = firestore.collection("users").document(uid).addSnapshotListener { [weak self] snapshot, error in
            guard let self else { return }
            self.lock.withLock {
                guard !self.cancelled else { return }
                if let error { self.failureLocked(error); return }
                guard snapshot?.metadata.hasPendingWrites == false else { return }
                self.profile = snapshot?.data().map(AccountAccess.fromMap)
                self.publishLocked()
            }
        }
        let access = firestore.collection("carCommunityAccess").document(uid).addSnapshotListener { [weak self] snapshot, error in
            guard let self else { return }
            self.lock.withLock {
                guard !self.cancelled else { return }
                if let error { self.failureLocked(error); return }
                guard snapshot?.metadata.hasPendingWrites == false else { return }
                self.product = snapshot?.data()
                self.publishLocked()
            }
            if snapshot?.metadata.isFromCache == false { self.refresh() }
        }
        lock.withLock {
            if cancelled { user.remove(); access.remove() }
            else { registrations = [user, access] }
        }
        refresh()
    }
    private func publishLocked() {
        guard !cancelled, auth.currentUser?.uid == uid,
              let profile, let product, let value = profile.withProduct(product) else { return }
        if let last {
            if value.productVersion < last.productVersion { return }
            if (last.carCommunitySuspended || last.blacklisted),
               !(value.carCommunitySuspended || value.blacklisted),
               value.productVersion <= last.productVersion { return }
        }
        last = value
        continuation.yield(.loaded(value))
    }
    private func failureLocked(_ error: Error) {
        if last == nil { continuation.yield(.failed(code: FirebaseEventsRepository.firestoreStatusName(error))) }
    }
    func refresh() {
        lock.withLock {
            guard !cancelled, !refreshing else { return }
            refreshing = true
            request = Task { [weak self] in
                guard let self else { return }
                do {
                    guard self.auth.currentUser?.uid == self.uid else { throw CancellationError() }
                    let response = try await self.functions.call("account-getAccess", payload: [:])
                    guard let value = response as? [String: Any], value["userId"] as? String == self.uid,
                          value["suspended"] is Bool, value["blacklisted"] is Bool else {
                        throw KccFunctionsError(code: .internalError)
                    }
                    self.lock.withLock {
                        guard !self.cancelled, self.auth.currentUser?.uid == self.uid else { return }
                        self.product = ["initialized": true,
                            "suspended": value["suspended"] as? Bool ?? false,
                            "inheritedBlocked": value["blacklisted"] as? Bool ?? false,
                            "suspensionReason": value["reason"] as? String ?? "",
                            "blacklistReason": value["reason"] as? String ?? "",
                            "version": (value["version"] as? NSNumber)?.intValue ?? 0]
                        self.publishLocked()
                    }
                } catch {
                    self.lock.withLock {
                        guard !self.cancelled, self.auth.currentUser?.uid == self.uid else { return }
                        let code = (error as? KccFunctionsError)?.code
                        if self.last == nil || code == .permissionDenied || code == .unauthenticated {
                            self.continuation.yield(.failed(code: code?.rawValue))
                        }
                    }
                }
                self.lock.withLock { self.refreshing = false }
            }
        }
    }
    func cancel() {
        let removed = lock.withLock { () -> [ListenerRegistration] in
            cancelled = true; request?.cancel(); request = nil
            let old = registrations; registrations = []; return old
        }
        removed.forEach { $0.remove() }
    }
}
