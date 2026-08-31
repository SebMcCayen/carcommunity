import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation

/// ``NotificationsRepository`` backed by Cloud Firestore + the notifications
/// callables — the iOS port of Android's `FirebaseNotificationsRepository`.
///
/// The inbox is an owner-only listener on `notifications/{uid}/items`, bounded
/// to the newest ``Notifications/inboxQueryLimit`` items (createdAt
/// descending — a single-field orderBy, so Firestore's automatic index
/// suffices); items are additionally sorted newest-first client-side
/// (``Notifications/sortedForInbox(_:)``). Read-state changes go through the
/// `notifications-markRead` / `-markAllRead` / `-markSeen` callables
/// (europe-west1, via ``KccFunctionsClient``), because the inbox is
/// backend-write-only.
///
/// Listener failures surface as ``NotificationsSnapshot/failed(code:)``
/// carrying the bare Firestore status name (reusing
/// ``FirebaseEventsRepository/firestoreStatusName(_:)``), never a silently
/// empty list — the same rule the events/garage listeners follow.
///
/// Construction is guarded (``createIfAvailable()`` returns nil without
/// Firebase config), mirroring ``FirebaseEventsRepository``.
///
/// Push delivery + token registration are OUT OF SCOPE: this type never
/// registers an APNs token or talks to FCM.
final class FirebaseNotificationsRepository: NotificationsRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: KccFunctionsClient

    private init(firestore: Firestore, functions: KccFunctionsClient) {
        self.firestore = firestore
        self.functions = functions
    }

    func notifications(uid: String) -> AsyncStream<NotificationsSnapshot> {
        let query =
            firestore
            .collection(Self.notificationsCollection)
            .document(uid)
            .collection(Self.itemsCollection)
            .order(by: Self.createdAtField, descending: true)
            .limit(to: Notifications.inboxQueryLimit)
        return AsyncStream { continuation in
            let registration = query.addSnapshotListener { snapshot, error in
                if let error {
                    // Bare status name only (never the exception text, which
                    // embeds the failing query and the project id) — see
                    // NotificationsSnapshot.failed.
                    continuation.yield(.failed(code: FirebaseEventsRepository.firestoreStatusName(error)))
                    return
                }
                let items = (snapshot?.documents ?? []).compactMap(Self.notification(from:))
                continuation.yield(.loaded(Notifications.sortedForInbox(items)))
            }
            let box = ListenerBox(registration: registration)
            continuation.onTermination = { _ in
                box.registration.remove()
            }
        }
    }

    func unread(uid: String) -> AsyncStream<Bool> {
        // Two cheap listeners, both bound while the dot is on screen: the
        // newest inbox item (a limit(1) createdAt-DESC query, the same
        // automatic index the inbox uses) and the caller's userPrivate
        // last-seen marker. The dot lights when the newest notification
        // post-dates the marker — Android's `observeUnread`.
        AsyncStream { continuation in
            let box = UnreadListenerBox()

            func emit() {
                continuation.yield(
                    Notifications.hasUnread(newest: box.newest, lastSeen: box.lastSeen)
                )
            }

            let newestRegistration =
                firestore
                .collection(Self.notificationsCollection)
                .document(uid)
                .collection(Self.itemsCollection)
                .order(by: Self.createdAtField, descending: true)
                .limit(to: 1)
                .addSnapshotListener { snapshot, error in
                    if let error {
                        // Access revoked (rules gating / restriction): hard-clear
                        // the newest instant so the dot never stays lit for an
                        // inbox the user can no longer read. A transient error
                        // WITH a cached snapshot falls through and uses it.
                        if FirebaseEventsRepository.firestoreStatusName(error) == "PERMISSION_DENIED" {
                            box.newest = nil
                            emit()
                            return
                        }
                        if snapshot == nil { return }
                    }
                    box.newest = (snapshot?.documents.first?.get(Self.createdAtField) as? Timestamp)?
                        .dateValue()
                    emit()
                }

            let markerRegistration =
                firestore
                .collection(Self.userPrivateCollection)
                .document(uid)
                .addSnapshotListener { snapshot, error in
                    // Transient failure with no cached marker: keep the
                    // last-known marker rather than momentarily reading it as
                    // missing, which could wrongly re-light the dot.
                    if error != nil, snapshot == nil { return }
                    box.lastSeen = (snapshot?.get(Self.lastSeenField) as? Timestamp)?.dateValue()
                    emit()
                }

            box.registrations = [newestRegistration, markerRegistration]
            continuation.onTermination = { _ in
                box.removeAll()
            }
        }
    }

    func markRead(notificationId: String) async throws {
        _ = try await functions.call(Self.markReadCallable, payload: ["notificationId": notificationId])
    }

    func markAllRead() async throws {
        _ = try await functions.call(Self.markAllReadCallable, payload: [:])
    }

    func markSeen() async throws {
        _ = try await functions.call(Self.markSeenCallable, payload: [:])
    }

    func currentUserId() -> String? {
        Auth.auth().currentUser?.uid
    }

    // MARK: - Mapping

    /// Tolerant document → item mapping — the iOS port of Android's
    /// `DocumentSnapshot.toNotification()`. The Firestore `Timestamp` is
    /// converted to a `Date` HERE (the Firebase-specific step) so the pure
    /// ``AppNotification/decode(id:fields:)`` decoder stays testable with a
    /// plain dictionary.
    static func notification(from document: DocumentSnapshot) -> AppNotification? {
        guard document.exists else { return nil }
        var fields = document.data() ?? [:]
        if let timestamp = document.get(createdAtField) as? Timestamp {
            fields[createdAtField] = timestamp.dateValue()
        } else {
            // A non-Timestamp value must not reach the pure decoder as a raw
            // Timestamp it cannot read; drop it so createdAt degrades to nil.
            fields[createdAtField] = nil
        }
        return AppNotification.decode(id: document.documentID, fields: fields)
    }

    // MARK: - Factory

    private static let notificationsCollection = "notifications"
    private static let itemsCollection = "items"
    private static let createdAtField = "createdAt"
    private static let userPrivateCollection = "userPrivate"
    private static let lastSeenField = "notificationsLastSeenAt"
    private static let markReadCallable = "notifications-markRead"
    private static let markAllReadCallable = "notifications-markAllRead"
    private static let markSeenCallable = "notifications-markSeen"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseNotificationsRepository?

    /// Returns the process-wide repository when Firebase is configured for
    /// this build, or nil when GoogleService-Info.plist is absent (CI, local
    /// validation builds — see apps/ios/README.md). Points the Firestore SDK
    /// at the emulator when `FIREBASE_FIRESTORE_EMULATOR_HOST` is set, the
    /// same seam ``FirebaseEventsRepository/createIfAvailable()`` provides.
    static func createIfAvailable() -> NotificationsRepository? {
        guard FirebaseApp.app() != nil, let functions = KccFunctionsClient.createIfAvailable() else {
            return nil
        }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        // The host check makes a second application of the SAME emulator
        // settings a no-op instead of mutating settings after Firestore has
        // already started (`Firestore.firestore()` is a shared singleton, and
        // other repositories' `createIfAvailable()` factories apply the same
        // settings) — the same guard `FirebaseVehiclesRepository` /
        // `FirebaseUserProfileRepository` use.
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ), firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseNotificationsRepository(firestore: firestore, functions: functions)
        cached = repository
        return repository
    }
}

/// `ListenerRegistration` is not Sendable, but the stream's `onTermination`
/// closure must be — all it does is remove the listener, which Firestore
/// documents as thread-safe, so the wrapper is sound (the same wrapper
/// ``FirebaseEventsRepository`` uses).
private struct ListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}

/// Holds the two ``FirebaseNotificationsRepository/unread(uid:)`` listeners'
/// last values and registrations.
///
/// Firestore does not GUARANTEE both listeners deliver on the same queue, so
/// `newest`/`lastSeen` are lock-protected rather than assumed
/// single-threaded — each is its own critical section, which is sufficient
/// here: a caller reads BOTH via ``FirebaseNotificationsRepository/unread(uid:)``'s
/// `emit()`, and a torn read (one field one callback behind the other)
/// self-heals on the very next emission from whichever listener fired.
private final class UnreadListenerBox: @unchecked Sendable {
    private let lock = NSLock()
    private var storedNewest: Date?
    private var storedLastSeen: Date?
    private var storedRegistrations: [ListenerRegistration] = []

    var newest: Date? {
        get { lock.withLock { storedNewest } }
        set { lock.withLock { storedNewest = newValue } }
    }

    var lastSeen: Date? {
        get { lock.withLock { storedLastSeen } }
        set { lock.withLock { storedLastSeen = newValue } }
    }

    var registrations: [ListenerRegistration] {
        get { lock.withLock { storedRegistrations } }
        set { lock.withLock { storedRegistrations = newValue } }
    }

    func removeAll() {
        let registrations = lock.withLock { () -> [ListenerRegistration] in
            let current = storedRegistrations
            storedRegistrations = []
            return current
        }
        for registration in registrations {
            registration.remove()
        }
    }
}
