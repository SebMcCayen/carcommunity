import FirebaseCore
import FirebaseFirestore
import Foundation

/// ``EventsRepository`` backed by Cloud Firestore — the iOS port of Android's
/// `FirebaseEventsRepository.kt` read path.
///
/// Published events are read with an equality filter (status == published)
/// ordered soonest-start-first and bounded to
/// ``Events/publishedEventsQueryLimit`` — the same soonest-first order the
/// list displays, so capping the query keeps exactly the events the screen
/// would show first as the collection grows without bound. Uses the existing
/// `events` composite index (status ASC, startsAt ASC —
/// firebase/firestore.indexes.json), so no new index is required.
///
/// Listener failures surface as ``EventsListSnapshot/failed(code:)`` carrying
/// the bare Firestore status name, never as a silently empty list — the two
/// deploy-gated failure modes Android documents (`FAILED_PRECONDITION` for a
/// missing index, `PERMISSION_DENIED` for an undeployed rule) must announce
/// themselves.
///
/// Construction is guarded (``createIfAvailable()`` returns nil without
/// Firebase config), mirroring `FirebaseAuthRepository` and Android's
/// `createIfAvailable`.
final class FirebaseEventsRepository: EventsRepository, @unchecked Sendable {
    private let firestore: Firestore

    private init(firestore: Firestore) {
        self.firestore = firestore
    }

    func publishedEvents() -> AsyncStream<EventsListSnapshot> {
        let query =
            firestore
            .collection(Self.eventsCollection)
            .whereField(Self.statusField, isEqualTo: EventStatus.published.wire)
            .order(by: Self.startsAtField)
            .limit(to: Events.publishedEventsQueryLimit)
        return AsyncStream { continuation in
            let registration = query.addSnapshotListener { snapshot, error in
                if let error {
                    // Bare status name only (never the exception text, which
                    // embeds the failing query and the project id) — see
                    // EventsListSnapshot.failed and Android's firestoreCode().
                    continuation.yield(.failed(code: Self.firestoreStatusName(error)))
                    return
                }
                let events = (snapshot?.documents ?? []).compactMap(Self.eventSummary(from:))
                continuation.yield(.loaded(Events.sortedForList(events)))
            }
            let box = ListenerBox(registration: registration)
            continuation.onTermination = { _ in
                box.registration.remove()
            }
        }
    }

    // MARK: - Mapping

    /// The bare Firestore status name (`FAILED_PRECONDITION`,
    /// `PERMISSION_DENIED`, `UNAVAILABLE`, …) for a listener error, or nil
    /// when the failure carries no Firestore code. A status name is the whole
    /// diagnosis and leaks nothing.
    static func firestoreStatusName(_ error: Error) -> String? {
        let nsError = error as NSError
        guard nsError.domain == FirestoreErrorDomain,
            let code = FirestoreErrorCode.Code(rawValue: nsError.code)
        else { return nil }
        switch code {
        case .OK: return "OK"
        case .cancelled: return "CANCELLED"
        case .unknown: return "UNKNOWN"
        case .invalidArgument: return "INVALID_ARGUMENT"
        case .deadlineExceeded: return "DEADLINE_EXCEEDED"
        case .notFound: return "NOT_FOUND"
        case .alreadyExists: return "ALREADY_EXISTS"
        case .permissionDenied: return "PERMISSION_DENIED"
        case .resourceExhausted: return "RESOURCE_EXHAUSTED"
        case .failedPrecondition: return "FAILED_PRECONDITION"
        case .aborted: return "ABORTED"
        case .outOfRange: return "OUT_OF_RANGE"
        case .unimplemented: return "UNIMPLEMENTED"
        case .internal: return "INTERNAL"
        case .unavailable: return "UNAVAILABLE"
        case .dataLoss: return "DATA_LOSS"
        case .unauthenticated: return "UNAUTHENTICATED"
        @unknown default: return nil
        }
    }

    /// Defensive teaser mapping — a doc without the required title/status is
    /// dropped, everything optional degrades to nil (Android's
    /// `toEventSummary`).
    static func eventSummary(from document: DocumentSnapshot) -> EventSummary? {
        guard document.exists,
            let title = document.get(titleField) as? String,
            let status = EventStatus.fromWire(document.get(statusField) as? String)
        else { return nil }
        return EventSummary(
            id: document.documentID,
            title: title,
            summary: document.get("summary") as? String,
            startsAt: (document.get(startsAtField) as? Timestamp)?.dateValue(),
            endsAt: (document.get("endsAt") as? Timestamp)?.dateValue(),
            approximateArea: document.get("approximateArea") as? String,
            // Public map location (2026-07): on the teaser so pins render
            // without the member gate.
            locationName: document.get("locationName") as? String,
            latitude: (document.get("latitude") as? NSNumber)?.doubleValue,
            longitude: (document.get("longitude") as? NSNumber)?.doubleValue,
            isOfficial: document.get("isOfficial") as? Bool ?? false,
            status: status,
            counts: RsvpCounts.fromMap(document.get("rsvpCounts") as? [String: Any])
        )
    }

    // MARK: - Factory

    private static let eventsCollection = "events"
    private static let statusField = "status"
    private static let startsAtField = "startsAt"
    private static let titleField = "title"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseEventsRepository?

    /// Returns the process-wide repository when Firebase is configured for
    /// this build, or nil when GoogleService-Info.plist is absent (CI, local
    /// validation builds — see apps/ios/README.md).
    ///
    /// When the `FIREBASE_FIRESTORE_EMULATOR_HOST` environment variable is
    /// set (e.g. `127.0.0.1:8080`, matching firebase.json's firestore
    /// emulator port), the Firestore SDK is pointed at the emulator before
    /// first use — the same seam `FIREBASE_AUTH_EMULATOR_HOST` provides for
    /// auth and `FIREBASE_FUNCTIONS_EMULATOR_HOST` for callables.
    static func createIfAvailable() -> EventsRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ) {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseEventsRepository(firestore: firestore)
        cached = repository
        return repository
    }
}

/// `ListenerRegistration` is not Sendable, but the stream's `onTermination`
/// closure must be — all it does is remove the listener, which Firestore
/// documents as thread-safe, so the wrapper is sound.
private struct ListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
