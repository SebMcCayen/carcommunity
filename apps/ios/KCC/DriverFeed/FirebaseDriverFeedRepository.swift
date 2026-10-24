import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
import Foundation

/// One server-authorized read per page; no direct content queries or legacy fallback.
final class FirebaseDriverFeedRepository: DriverFeedRepository, @unchecked Sendable {
    private let db: Firestore
    private let auth: Auth
    private let functions: Functions

    private init(db: Firestore, auth: Auth, functions: Functions) {
        self.db = db
        self.auth = auth
        self.functions = functions
    }

    func feed(uid: String, after: DriverCursor?) async throws -> DriverPage<DriverPost> {
        let fields = try await read(uid: uid, name: "driverFeed-list", payload: driverPagePayload(after))
        let posts = try driverWireList(fields, key: "posts").map { value -> DriverPost in
            guard let id = value["id"] as? String, let post = DriverPost.decode(id: id, fields: value) else {
                throw DriverFeedFailure.error
            }
            return post
        }
        return DriverPage(items: posts, next: try driverWireCursor(fields))
    }

    func detail(uid: String, id: String, after: DriverCursor?) async throws -> DriverDetailPage {
        guard validDriverId(id) else { throw DriverFeedFailure.unavailable }
        var payload = driverPagePayload(after)
        payload["threadId"] = id
        let fields = try await read(uid: uid, name: "driverFeed-detail", payload: payload)
        guard let raw = fields["post"] as? [String: Any], raw["id"] as? String == id,
            let post = DriverPost.decode(id: id, fields: raw) else { throw DriverFeedFailure.error }
        let comments = try driverWireList(fields, key: "comments").map { value -> DriverComment in
            guard let id = value["id"] as? String, validDriverId(id),
                let authorUid = value["authorUid"] as? String, validDriverId(authorUid),
                let author = value["authorName"] as? String, let text = value["text"] as? String,
                let millis = value["createdAtMillis"] as? NSNumber else { throw DriverFeedFailure.error }
            return DriverComment(id: id, authorUid: authorUid, author: author, text: text,
                createdAt: Date(timeIntervalSince1970: millis.doubleValue / 1000))
        }
        return DriverDetailPage(post: post, comments: DriverPage(items: comments, next: try driverWireCursor(fields)))
    }

    private func read(uid: String, name: String, payload: [String: Any]) async throws -> [String: Any] {
        guard let user = auth.currentUser, user.uid == uid else { throw DriverFeedFailure.restricted }
        let result: HTTPSCallableResult
        do {
            result = try await functions.httpsCallable(name).call(payload)
        } catch {
            try fence(user)
            let nsError = error as NSError
            let reason = (nsError.userInfo[FunctionsErrorDetailsKey] as? [String: Any])?["reason"] as? String
            var code = "UNKNOWN"
            if nsError.domain == FunctionsErrorDomain, let value = FunctionsErrorCode(rawValue: nsError.code) {
                switch value {
                case .unauthenticated: code = "UNAUTHENTICATED"
                case .permissionDenied: code = "PERMISSION_DENIED"
                case .notFound: code = "NOT_FOUND"
                default: break
                }
            }
            throw driverReadFailure(reason: reason, code: code)
        }
        try fence(user)
        guard let fields = result.data as? [String: Any] else { throw DriverFeedFailure.error }
        return fields
    }

    private func fence(_ user: User) throws {
        try Task.checkCancellation()
        guard auth.currentUser === user else { throw CancellationError() }
    }

    // Listeners invalidate memory only. All visible content is returned by the bridge.
    func invalidations(uid: String, threadId: String?) -> AsyncStream<Void> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let authHandle = auth.addIDTokenDidChangeListener { _, _ in continuation.yield(()) }
            var documents = [db.collection("users").document(uid), db.collection("driverUsers").document(uid),
                db.collection("blockVisibility").document(uid)]
            if let threadId { documents.append(db.collection("driverThreads").document(threadId)) }
            let registrations = documents.map { doc in
                doc.addSnapshotListener { _, _ in continuation.yield(()) }
            } + [db.collection("driverUsers").document(uid).collection("blockedUsers").limit(to: 101)
                .addSnapshotListener { _, _ in continuation.yield(()) }]
            let box = DriverFeedListeners(auth: auth, authHandle: authHandle, registrations: registrations)
            continuation.onTermination = { _ in box.remove() }
        }
    }

    static func createIfAvailable() -> (any DriverFeedRepository)? {
        guard FirebaseApp.app() != nil else { return nil }
        let db = Firestore.firestore()
        let functions = Functions.functions(region: "europe-west1")
        if let emulator = FirebaseEmulatorHost.parse(ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]),
            db.settings.host != "\(emulator.host):\(emulator.port)" {
            db.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        if let emulator = FirebaseEmulatorHost.parse(ProcessInfo.processInfo.environment["FIREBASE_FUNCTIONS_EMULATOR_HOST"]) {
            functions.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        return FirebaseDriverFeedRepository(db: db, auth: Auth.auth(), functions: functions)
    }
}

private struct DriverFeedListeners: @unchecked Sendable {
    let auth: Auth
    let authHandle: any NSObjectProtocol
    let registrations: [ListenerRegistration]
    func remove() {
        auth.removeIDTokenDidChangeListener(authHandle)
        registrations.forEach { $0.remove() }
    }
}
