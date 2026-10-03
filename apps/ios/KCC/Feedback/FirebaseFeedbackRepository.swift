import FirebaseCore
import FirebaseFirestore
import Foundation

final class FirebaseFeedbackRepository: FeedbackRepository, @unchecked Sendable {
    private let functions: KccFunctionsClient

    private init(functions: KccFunctionsClient) {
        self.functions = functions
    }

    func report(_ input: FeedbackReportInput) async throws -> FeedbackSubmitResult {
        var payload: [String: Any] = ["description": input.description, "platform": "ios"]
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

    func tickets(afterNumber: Int?, limit: Int) async -> OpenTicketsPageResult {
        guard limit > 0 else { return .loaded(OpenTicketsPage(tickets: [], nextCursor: nil)) }
        var query: Query = firestore.collection(Self.collection)
            .order(by: "number", descending: true)
        if let afterNumber { query = query.whereField("number", isLessThan: afterNumber) }
        do {
            let snapshot = try await query.limit(to: limit + 1).getDocuments()
            let pageDocuments = Array(snapshot.documents.prefix(limit))
            let tickets = pageDocuments.compactMap {
                OpenTicket.decode(documentId: $0.documentID, fields: $0.data())
            }
            let cursor = snapshot.documents.count > limit
                ? pageDocuments.last.flatMap { Self.positiveInteger($0.data()["number"]) }
                : nil
            return .loaded(OpenTicketsPage(tickets: tickets, nextCursor: cursor))
        } catch {
            return .failed
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
            return Self.interactionOutcome(from: error)
        } catch {
            return .failed
        }
    }

    /// Only the backend's stable duplicate discriminator means the member has
    /// already completed the action. Other failed preconditions include a
    /// disabled feature and a closed ticket, so they remain generic failures.
    static func interactionOutcome(from error: KccFunctionsError) -> TicketInteractionOutcome {
        if error.code == .failedPrecondition, error.reason == .ticketAlreadyInteracted {
            return .alreadyDone
        }
        if error.code == .resourceExhausted {
            return .rateLimited
        }
        return .failed
    }

    private static let collection = "openTickets"
    private static let interactCallable = "feedback-interactWithIssue"

    private static func positiveInteger(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber else { return nil }
        let double = number.doubleValue
        guard double.isFinite, double.rounded() == double,
              double > 0, double <= Double(Int.max)
        else { return nil }
        return Int(double)
    }

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
