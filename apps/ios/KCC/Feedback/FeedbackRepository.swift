import Foundation

protocol FeedbackRepository: Sendable {
    func report(_ input: FeedbackReportInput) async throws -> FeedbackSubmitResult
}

protocol OpenTicketsRepository: Sendable {
    func tickets(afterNumber: Int?, limit: Int) async -> OpenTicketsPageResult
    func interact(
        issueNumber: Int,
        type: TicketInteractionType,
        text: String?,
        clientId: String
    ) async -> TicketInteractionOutcome
}

struct OpenTicket: Equatable, Identifiable, Sendable {
    let number: Int
    let title: String
    let summary: String
    let htmlURL: URL
    let plusOneCount: Int
    let commentCount: Int

    var id: Int { number }

    func incrementing(_ type: TicketInteractionType) -> OpenTicket {
        let nextPlusOne = type == .plusOne && plusOneCount < Int.max
            ? plusOneCount + 1 : plusOneCount
        let nextComment = type == .comment && commentCount < Int.max
            ? commentCount + 1 : commentCount
        return OpenTicket(
            number: number,
            title: title,
            summary: summary,
            htmlURL: htmlURL,
            plusOneCount: nextPlusOne,
            commentCount: nextComment
        )
    }

    static func decode(documentId: String, fields: [String: Any]) -> OpenTicket? {
        let number = Self.integer(fields["number"]) ?? Int(documentId)
        guard let number, number > 0,
              let title = fields["title"] as? String,
              !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let htmlURL = GitHubIssueLinks.safeURL(fields["htmlUrl"] as? String),
              (fields["state"] as? String)?.lowercased() != "closed"
        else { return nil }

        return OpenTicket(
            number: number,
            title: FeedbackText.prefixByUTF16(title, limit: 256),
            summary: FeedbackText.prefixByUTF16(fields["summary"] as? String ?? "", limit: 140),
            htmlURL: htmlURL,
            plusOneCount: max(0, Self.integer(fields["plusOneCount"]) ?? 0),
            commentCount: max(0, Self.integer(fields["commentCount"]) ?? 0)
        )
    }

    private static func integer(_ value: Any?) -> Int? {
        if let value = value as? Int { return value }
        if let value = value as? Int64,
           value <= Int64(Int.max), value >= Int64(Int.min) { return Int(value) }
        if let value = value as? NSNumber {
            let double = value.doubleValue
            guard double.isFinite, double.rounded() == double,
                  double <= Double(Int.max), double >= Double(Int.min)
            else { return nil }
            return Int(double)
        }
        return nil
    }
}

struct OpenTicketsPage: Equatable, Sendable {
    let tickets: [OpenTicket]
    let nextCursor: Int?
}

enum OpenTicketsPageResult: Equatable, Sendable {
    case loaded(OpenTicketsPage)
    case failed
}

enum OpenTicketsListState: Equatable, Sendable {
    case loading
    case loaded([OpenTicket])
    case failed
    case unavailable
}

enum TicketInteractionType: Equatable, Sendable {
    case plusOne
    case comment

    var wireValue: String { self == .plusOne ? "plus_one" : "comment" }
}

enum TicketInteractionOutcome: Equatable, Sendable {
    case posted
    case deliveryFailed
    case alreadyDone
    case rateLimited
    case failed
}

enum TicketInteractionError: Equatable, Sendable {
    case alreadyDone
    case rateLimited
    case emptyComment
    case unknown
}

struct TicketInteractionState: Equatable, Sendable {
    var plusOneDone = false
    var commentDone = false
    var plusOneDeliveryFailed = false
    var commentDeliveryFailed = false
    var submitting: TicketInteractionType?
    var error: TicketInteractionError?

    var canPlusOne: Bool { !plusOneDone && !plusOneDeliveryFailed && submitting == nil }
    var canComment: Bool { !commentDone && !commentDeliveryFailed && submitting == nil }
}

enum TicketComments {
    static let maximumLength = 1_000

    static func bound(_ text: String) -> String {
        FeedbackText.boundMultiline(text, limit: maximumLength)
    }
}
