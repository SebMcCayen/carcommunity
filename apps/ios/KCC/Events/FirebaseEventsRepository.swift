import FirebaseAuth
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
    private let functions: KccFunctionsClient

    private init(firestore: Firestore, functions: KccFunctionsClient) {
        self.firestore = firestore
        self.functions = functions
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

    func event(withId eventId: String) -> AsyncStream<EventSummary?> {
        documentStream(
            firestore.collection(Self.eventsCollection).document(eventId),
            map: Self.eventSummary(from:)
        )
    }

    func eventDetail(eventId: String) -> AsyncStream<EventPrivateDetailSnapshot> {
        let reference = firestore
            .collection(Self.eventsCollection)
            .document(eventId)
            .collection(Self.detailsCollection)
            .document(Self.privateDocument)
        return AsyncStream { continuation in
            let registration = reference.addSnapshotListener { snapshot, error in
                if let error {
                    continuation.yield(.failed(code: Self.firestoreStatusName(error)))
                    return
                }
                guard let snapshot else {
                    continuation.yield(.failed(code: nil))
                    return
                }
                continuation.yield(.loaded(Self.eventDetail(from: snapshot)))
            }
            let box = ListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func myRsvp(eventId: String, uid: String) -> AsyncStream<RsvpStatus?> {
        documentStream(rsvpDocument(eventId: eventId, uid: uid)) { document in
            RsvpStatus.fromWire(document.get(Self.statusField) as? String)
        }
    }

    func myAttendance(eventId: String, uid: String) -> AsyncStream<EventAttendanceStatus?> {
        let reference = firestore.collection(Self.attendanceCollection).document("\(eventId)__\(uid)")
        return AsyncStream { continuation in
            let registration = reference.addSnapshotListener { snapshot, error in
                // Keep the last UI state on a transient failure. Nil is reserved
                // for a successfully read, genuinely absent record.
                guard error == nil, let snapshot else { return }
                guard snapshot.exists else {
                    continuation.yield(nil)
                    return
                }
                continuation.yield(
                    EventAttendanceStatus(
                        verified: snapshot.get(Self.verifiedField) as? Bool ?? false,
                        sampleCount: max(0, (snapshot.get(Self.sampleCountField) as? NSNumber)?.intValue ?? 0),
                        recordCreatedAt: (snapshot.get(Self.createdAtField) as? Timestamp)?.dateValue()
                    )
                )
            }
            let box = ListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func submitRsvp(eventId: String, uid: String, status: RsvpStatus) async throws {
        // The exact rules-validated shape (firestore.rules validRsvpDocument):
        // `{ status, updatedAt: serverTimestamp }`, nothing else — Android's
        // `setRsvp`. The events-onRsvpWrite trigger maintains rsvpCounts.
        let document: [String: Any] = [
            Self.statusField: status.wire,
            Self.updatedAtField: FieldValue.serverTimestamp(),
        ]
        do {
            try await rsvpDocument(eventId: eventId, uid: uid).setData(document)
        } catch {
            // Bare status name only — the same PII-safe rule as the listener
            // failures (the SDK message embeds the document path).
            throw RsvpWriteError(code: Self.firestoreStatusName(error))
        }
    }

    func currentUserId() -> String? {
        Auth.auth().currentUser?.uid
    }

    func createEvent(_ input: EventFormInput) async throws -> String {
        do {
            let raw = try await functions.call(Self.createCallable, payload: Events.createPayload(input))
            guard let eventId = (raw as? [String: Any])?["eventId"] as? String,
                  !eventId.isEmpty else {
                throw CreateEventError(reason: .unknown)
            }
            return eventId
        } catch let error as KccFunctionsError {
            throw CreateEventError(
                reason: error.code == .resourceExhausted ? .rateLimited : .unknown
            )
        }
    }

    func updateEvent(eventId: String, input: EventFormInput) async throws {
        do {
            _ = try await functions.call(
                Self.updateCallable,
                payload: Events.updatePayload(eventId: eventId, input: input)
            )
        } catch let error as KccFunctionsError {
            throw ManageEventError(reason: Self.manageFailure(error.code))
        }
    }

    func cancelEvent(eventId: String) async throws {
        do {
            _ = try await functions.call(
                Self.cancelCallable,
                payload: ["eventId": eventId, "reason": "Removed by event creator"]
            )
        } catch let error as KccFunctionsError {
            throw ManageEventError(reason: Self.manageFailure(error.code))
        }
    }

    func attendees(eventId: String) async -> EventAttendeesResult {
        do {
            let raw = try await functions.call(
                Self.attendeesCallable,
                payload: ["eventId": eventId]
            )
            guard let map = raw as? [String: Any],
                  let rows = map["attendees"] as? [[String: Any]],
                  let requiresPaid = map["requiresPaid"] as? Bool
            else { return .failed }
            if requiresPaid { return .requiresPaid }
            let attendees = rows.compactMap { row -> EventAttendee? in
                guard let id = row["userId"] as? String,
                      let status = RsvpStatus.fromWire(row["status"] as? String)
                else { return nil }
                return EventAttendee(
                    id: id,
                    displayName: row["displayName"] as? String,
                    avatarPath: row["avatarPath"] as? String,
                    status: status
                )
            }
            return .loaded(attendees.sorted { left, right in
                let leftName = left.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
                let rightName = right.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
                switch (leftName?.isEmpty == false ? leftName : nil,
                        rightName?.isEmpty == false ? rightName : nil) {
                case let (lhs?, rhs?):
                    let order = lhs.localizedCaseInsensitiveCompare(rhs)
                    return order == .orderedSame ? left.id < right.id : order == .orderedAscending
                case (.some, nil): return true
                case (nil, .some): return false
                case (nil, nil): return left.id < right.id
                }
            })
        } catch let error as KccFunctionsError
            where [.notFound, .permissionDenied, .unauthenticated].contains(error.code) {
            return .unavailable
        } catch {
            return .failed
        }
    }

    func checkIn(eventId: String, fix: EventCheckInFix) async throws -> EventCheckInResult {
        var payload: [String: Any] = [
            "eventId": eventId,
            "latitude": fix.latitude,
            "longitude": fix.longitude,
            "capturedAt": Events.iso8601(fix.capturedAt),
            "isMockLocation": fix.isMock,
        ]
        if let accuracy = fix.accuracyMeters { payload["accuracyMeters"] = accuracy }
        let raw = try await functions.call(Self.checkInCallable, payload: payload)
        guard let value = (raw as? [String: Any])?["result"] as? String else { return .unknown }
        return EventCheckInResult(rawValue: value) ?? .unknown
    }

    private static func manageFailure(_ code: KccFunctionsErrorCode) -> ManageEventFailure {
        switch code {
        case .permissionDenied: .permissionDenied
        case .failedPrecondition: .immutable
        default: .unknown
        }
    }

    private func rsvpDocument(eventId: String, uid: String) -> DocumentReference {
        firestore
            .collection(Self.eventsCollection)
            .document(eventId)
            .collection(Self.rsvpsCollection)
            .document(uid)
    }

    /// One document's snapshot listener as a stream of mapped values —
    /// Android's `observeEvent` / `observeEventDetail` / `observeMyRsvp`
    /// shape: a listener error or a missing document emits nil (a non-member
    /// denied `details/private` must read as "no detail", not a crash), and
    /// terminating the stream detaches the listener.
    private func documentStream<Value: Sendable>(
        _ document: DocumentReference,
        map: @escaping @Sendable (DocumentSnapshot) -> Value?
    ) -> AsyncStream<Value?> {
        AsyncStream { continuation in
            let registration = document.addSnapshotListener { snapshot, error in
                // Error first, like every other listener in this codebase: a
                // failed read is "no value", never a stale snapshot.
                guard error == nil, let snapshot else {
                    continuation.yield(nil)
                    return
                }
                continuation.yield(map(snapshot))
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
            counts: RsvpCounts.fromMap(document.get("rsvpCounts") as? [String: Any]),
            createdByUserId: document.get("createdByUserId") as? String
        )
    }

    /// Member-gated detail mapping — a missing document is nil; both fields
    /// degrade to nil independently (Android's `toEventDetail`).
    static func eventDetail(from document: DocumentSnapshot) -> EventDetail? {
        guard document.exists else { return nil }
        return EventDetail(
            description: document.get("description") as? String,
            address: document.get("address") as? String
        )
    }

    // MARK: - Factory

    private static let eventsCollection = "events"
    private static let detailsCollection = "details"
    private static let privateDocument = "private"
    private static let rsvpsCollection = "rsvps"
    private static let statusField = "status"
    private static let startsAtField = "startsAt"
    private static let updatedAtField = "updatedAt"
    private static let titleField = "title"
    private static let createCallable = "events-create"
    private static let updateCallable = "events-update"
    private static let cancelCallable = "events-cancel"
    private static let attendeesCallable = "events-listAttendees"
    private static let checkInCallable = "events-checkIn"
    private static let attendanceCollection = "eventAttendance"
    private static let verifiedField = "verified"
    private static let sampleCountField = "sampleCount"
    private static let createdAtField = "createdAt"

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
        guard let functions = KccFunctionsClient.createIfAvailable() else { return nil }
        let repository = FirebaseEventsRepository(firestore: firestore, functions: functions)
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
