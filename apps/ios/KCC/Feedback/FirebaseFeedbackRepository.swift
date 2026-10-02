import FirebaseCore
import FirebaseFirestore
import Foundation

final class FirebaseFeedbackRepository: FeedbackRepository, @unchecked Sendable {
    private let functions: KccFunctionsClient

    private init(functions: KccFunctionsClient) {
        self.functions = functions
    }

    func report(_ input: FeedbackReportInput) async throws -> FeedbackSubmitResult {
        var payload: [String: Any] = ["description": input.description]
        if let summary = input.summary { payload["summary"] = summary }
        if let appVersion = input.appVersion { payload["appVersion"] = appVersion }
        if let osVersion = input.osVersion { payload["osVersion"] = osVersion }
        if let deviceModel = input.deviceModel { payload["deviceModel"] = deviceModel }

        let raw = try await functions.call(Self.callable, payload: payload)
        guard let response = raw as? [String: Any],
              let reportId = response["reportId"] as? String,
              !reportId.isEmpty
        else { throw KccFunctionsError(code: .internalError) }
        return FeedbackSubmitResult(
            reportId: reportId,
            issueURL: GitHubIssueLinks.safeURL(response["githubIssueUrl"] as? String),
            issueNumber: Self.positiveInteger(response["githubIssueNumber"])
        )
    }

    private static func positiveInteger(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber else { return nil }
        let double = number.doubleValue
        guard double.isFinite, double.rounded() == double,
              double > 0, double <= Double(Int.max)
        else { return nil }
        return Int(double)
    }

    private static let callable = "feedback-reportIssue"

    static func createIfAvailable() -> FeedbackRepository? {
        guard FirebaseApp.app() != nil,
              let functions = KccFunctionsClient.createIfAvailable()
        else { return nil }
        return FirebaseFeedbackRepository(functions: functions)
    }
}

final class FirebaseOpenTicketsRepository: OpenTicketsRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: KccFunctionsClient

    private init(firestore: Firestore, functions: KccFunctionsClient) {
        self.firestore = firestore
        self.functions = functions
    }

    func tickets() -> AsyncStream<OpenTicketsSnapshot> {
        let query = firestore.collection(Self.collection).order(by: "number", descending: true)
        return AsyncStream { continuation in
            let state = OpenTicketsListenerState()
            let registration = query.addSnapshotListener { snapshot, error in
                if error != nil {
                    if !state.hasLoaded { continuation.yield(.failed) }
                    return
                }
                let tickets = (snapshot?.documents ?? []).compactMap {
                    OpenTicket.decode(documentId: $0.documentID, fields: $0.data())
                }
                state.hasLoaded = true
                continuation.yield(.loaded(tickets))
            }
            let box = FeedbackListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func interact(
        issueNumber: Int,
        type: TicketInteractionType,
        text: String?,
        clientId: String
    ) async -> TicketInteractionOutcome {
        var payload: [String: Any] = [
            "issueNumber": issueNumber,
            "type": type.wireValue,
            "clientId": clientId
        ]
        if type == .comment, let text { payload["text"] = text }
        do {
            _ = try await functions.call(Self.interactCallable, payload: payload)
            return .posted
        } catch let error as KccFunctionsError {
            switch error.code {
            case .failedPrecondition: return .alreadyDone
            case .resourceExhausted: return .rateLimited
            default: return .failed
            }
        } catch {
            return .failed
        }
    }

    private static let collection = "openTickets"
    private static let interactCallable = "feedback-interactWithIssue"

    static func createIfAvailable() -> OpenTicketsRepository? {
        guard FirebaseApp.app() != nil,
              let functions = KccFunctionsClient.createIfAvailable()
        else { return nil }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ), firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        return FirebaseOpenTicketsRepository(firestore: firestore, functions: functions)
    }
}

private final class OpenTicketsListenerState: @unchecked Sendable {
    private let lock = NSLock()
    private var storedHasLoaded = false

    var hasLoaded: Bool {
        get { lock.withLock { storedHasLoaded } }
        set { lock.withLock { storedHasLoaded = newValue } }
    }
}

private struct FeedbackListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}

