import XCTest

@testable import KCC

final class FeedbackFeatureTests: XCTestCase {
    private final class FakeFeedbackRepository: FeedbackRepository, @unchecked Sendable {
        var result: Result<FeedbackSubmitResult, Error>
        var inputs: [FeedbackReportInput] = []

        init(result: Result<FeedbackSubmitResult, Error>) { self.result = result }

        func report(_ input: FeedbackReportInput) async throws -> FeedbackSubmitResult {
            inputs.append(input)
            return try result.get()
        }
    }

    private final class FakeTicketsRepository: OpenTicketsRepository, @unchecked Sendable {
        var pages: [OpenTicketsPageResult] = []
        var pageCalls: [(Int?, Int)] = []
        var outcomes: [TicketInteractionOutcome] = []
        var calls: [(Int, TicketInteractionType, String?, String)] = []

        func tickets(afterNumber: Int?, limit: Int) async -> OpenTicketsPageResult {
            pageCalls.append((afterNumber, limit))
            return pages.isEmpty ? .failed : pages.removeFirst()
        }

        func interact(
            issueNumber: Int,
            type: TicketInteractionType,
            text: String?,
            clientId: String
        ) async -> TicketInteractionOutcome {
            calls.append((issueNumber, type, text, clientId))
            return outcomes.isEmpty ? .failed : outcomes.removeFirst()
        }
    }

    func testReportInputStripsControlsBoundsUTF16AndSanitizesContext() {
        let context = FeedbackClientContext.sanitized(
            appVersion: "  1.2\nsecret  ",
            osVersion: "iOS\u{0000} 26",
            deviceModel: "  iPhone\t Pro "
        )
        let input = FeedbackReports.input(
            from: FeedbackReportForm(
                summary: String(repeating: "🚗", count: 50),
                description: "  First\u{0000} line\nsecond  "
            ),
            context: context
        )

        XCTAssertEqual(input?.summary?.utf16.count, 80)
        XCTAssertEqual(input?.description, "First line\nsecond")
        XCTAssertEqual(input?.appVersion, "1.2 secret")
        XCTAssertEqual(input?.osVersion, "iOS 26")
        XCTAssertEqual(input?.deviceModel, "iPhone Pro")
    }

    func testWhitespaceOnlyDescriptionIsRejected() {
        XCTAssertEqual(
            FeedbackReports.validate(FeedbackReportForm(summary: "x", description: " \n\t ")),
            .descriptionRequired
        )
    }

    func testGitHubLinkGuardRejectsNonWebCredentialsPortsAndLookalikes() {
        XCTAssertNotNil(GitHubIssueLinks.safeURL("https://github.com/SebMcCayen/carcommunity/issues/1"))
        XCTAssertNil(GitHubIssueLinks.safeURL("https://gist.github.com/example/1"))
        XCTAssertNil(GitHubIssueLinks.safeURL("http://github.com/SebMcCayen/carcommunity/issues/1"))
        XCTAssertNil(GitHubIssueLinks.safeURL("https://github.com/other/repo/issues/1"))
        XCTAssertNil(GitHubIssueLinks.safeURL("javascript:alert(1)"))
        XCTAssertNil(GitHubIssueLinks.safeURL("https://github.com.evil.test/issues/1"))
        XCTAssertNil(GitHubIssueLinks.safeURL("https://user@github.com/issues/1"))
        XCTAssertNil(GitHubIssueLinks.safeURL("https://github.com:8443/issues/1"))
    }

    @MainActor
    func testTicketCoordinatorLoadsBoundedCursorPages() async {
        let repository = FakeTicketsRepository()
        let first = OpenTicket(number: 42, title: "First", summary: "", htmlURL: URL(string: "https://github.com/SebMcCayen/carcommunity/issues/42")!, plusOneCount: 0, commentCount: 0)
        let second = OpenTicket(number: 41, title: "Second", summary: "", htmlURL: URL(string: "https://github.com/SebMcCayen/carcommunity/issues/41")!, plusOneCount: 0, commentCount: 0)
        repository.pages = [
            .loaded(OpenTicketsPage(tickets: [first], nextCursor: 42)),
            .loaded(OpenTicketsPage(tickets: [second], nextCursor: nil))
        ]
        let coordinator = OpenTicketsCoordinator(repository: repository)
        coordinator.start()
        await Task.yield()
        await Task.yield()
        XCTAssertTrue(coordinator.canLoadMore)
        coordinator.loadMore()
        await Task.yield()
        await Task.yield()
        XCTAssertEqual(repository.pageCalls.map(\.0), [nil, 42])
        XCTAssertEqual(repository.pageCalls.map(\.1), [25, 25])
        XCTAssertEqual(coordinator.listState, .loaded([first, second]))
    }

    func testTicketDecodeRequiresPositiveNumberTitleAndSafeURL() {
        let valid = OpenTicket.decode(documentId: "42", fields: [
            "title": "Problem", "summary": "Summary",
            "htmlUrl": "https://github.com/SebMcCayen/carcommunity/issues/42",
            "state": "open", "plusOneCount": -1, "commentCount": 3
        ])
        XCTAssertEqual(valid?.number, 42)
        XCTAssertEqual(valid?.plusOneCount, 0)
        XCTAssertEqual(valid?.commentCount, 3)
        XCTAssertNil(OpenTicket.decode(documentId: "0", fields: [
            "title": "Problem", "htmlUrl": "https://github.com/issues/0"
        ]))
        XCTAssertNil(OpenTicket.decode(documentId: "43", fields: [
            "title": "Problem", "htmlUrl": "file:///tmp/report"
        ]))
        XCTAssertNil(OpenTicket.decode(documentId: "44", fields: [
            "title": "Problem", "htmlUrl": "https://github.com/issues/44", "state": "closed"
        ]))
    }

    @MainActor
    func testFeedbackCoordinatorCarriesSafeSuccessAndMapsContractErrors() async {
        let repository = FakeFeedbackRepository(result: .success(FeedbackSubmitResult(
            reportId: "r1",
            issueURL: URL(string: "https://github.com/SebMcCayen/carcommunity/issues/1"),
            issueNumber: 1
        )))
        let coordinator = FeedbackCoordinator(repository: repository)
        await coordinator.submit(
            form: FeedbackReportForm(summary: "Summary", description: "Description"),
            context: .sanitized(appVersion: "1", osVersion: "iOS", deviceModel: "iPhone")
        )
        XCTAssertEqual(
            coordinator.status,
            .submitted(
                issueURL: URL(string: "https://github.com/SebMcCayen/carcommunity/issues/1"),
                issueNumber: 1,
                summary: "Summary"
            )
        )

        repository.result = .failure(KccFunctionsError(code: .resourceExhausted))
        coordinator.reset()
        await coordinator.submit(
            form: FeedbackReportForm(description: "Again"),
            context: .sanitized(appVersion: nil, osVersion: nil, deviceModel: nil)
        )
        XCTAssertEqual(coordinator.status, .failed(.rateLimited))
    }

    @MainActor
    func testTicketCoordinatorBoundsCommentsAndDisablesCompletedActions() async {
        let repository = FakeTicketsRepository()
        repository.outcomes = [.posted, .alreadyDone]
        let coordinator = OpenTicketsCoordinator(repository: repository)

        await coordinator.plusOne(issueNumber: 7)
        await coordinator.plusOne(issueNumber: 7)
        XCTAssertTrue(coordinator.interactions[7]?.plusOneDone == true)
        XCTAssertEqual(repository.calls.filter { $0.1 == .plusOne }.count, 1)

        await coordinator.comment(
            issueNumber: 7,
            text: "  " + String(repeating: "a", count: 1_100) + "  "
        )
        XCTAssertTrue(coordinator.interactions[7]?.commentDone == true)
        XCTAssertEqual(repository.calls.last?.2?.utf16.count, TicketComments.maximumLength)
        XCTAssertEqual(coordinator.interactions[7]?.error, .alreadyDone)
        XCTAssertEqual(repository.calls.last?.3.count, 32)
    }

    @MainActor
    func testTicketCoordinatorSurfacesEmptyAndRateLimitedWithoutRawErrors() async {
        let repository = FakeTicketsRepository()
        repository.outcomes = [.rateLimited]
        let coordinator = OpenTicketsCoordinator(repository: repository)

        await coordinator.comment(issueNumber: 9, text: " \n ")
        XCTAssertEqual(coordinator.interactions[9]?.error, .emptyComment)
        XCTAssertTrue(repository.calls.isEmpty)

        await coordinator.plusOne(issueNumber: 9)
        XCTAssertEqual(coordinator.interactions[9]?.error, .rateLimited)
        XCTAssertTrue(coordinator.interactions[9]?.canPlusOne == true)
    }
}
