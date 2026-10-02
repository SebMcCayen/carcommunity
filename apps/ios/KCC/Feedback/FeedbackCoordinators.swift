import Foundation
import Observation

@MainActor
@Observable
final class FeedbackCoordinator {
    private let repository: FeedbackRepository?
    private(set) var status: FeedbackStatus

    init(repository: FeedbackRepository?) {
        self.repository = repository
        status = repository == nil ? .unavailable : .idle
    }

    func submit(form: FeedbackReportForm, context: FeedbackClientContext) async {
        guard status != .submitting,
              let repository,
              let input = FeedbackReports.input(from: form, context: context)
        else { return }
        status = .submitting
        do {
            let result = try await repository.report(input)
            status = .submitted(
                issueURL: result.issueURL,
                issueNumber: result.issueNumber,
                summary: input.summary
            )
        } catch is CancellationError {
            status = .idle
        } catch let error as KccFunctionsError {
            status = .failed(Self.failure(for: error.code))
        } catch {
            status = .failed(.unknown)
        }
    }

    func reset() {
        guard status != .submitting else { return }
        status = repository == nil ? .unavailable : .idle
    }

    private static func failure(for code: KccFunctionsErrorCode) -> FeedbackFailure {
        switch code {
        case .resourceExhausted: .rateLimited
        case .unauthenticated: .signedOut
        case .unavailable: .unavailable
        default: .unknown
        }
    }
}

@MainActor
@Observable
final class OpenTicketsCoordinator {
    private let repository: OpenTicketsRepository?
    @ObservationIgnored nonisolated(unsafe) private var loadTask: Task<Void, Never>?
    private var nextCursor: Int?
    private(set) var isLoadingMore = false
    private(set) var listState: OpenTicketsListState
    private(set) var interactions: [Int: TicketInteractionState] = [:]

    init(repository: OpenTicketsRepository?) {
        self.repository = repository
        listState = repository == nil ? .unavailable : .loading
    }

    deinit { loadTask?.cancel() }

    func start() {
        guard loadTask == nil, repository != nil else { return }
        listState = .loading
        nextCursor = nil
        loadPage(append: false)
    }

    var canLoadMore: Bool { nextCursor != nil && !isLoadingMore }

    func loadMore() {
        guard canLoadMore else { return }
        loadPage(append: true)
    }

    func stop() {
        loadTask?.cancel()
        loadTask = nil
        isLoadingMore = false
    }

    private func loadPage(append: Bool) {
        guard let repository else { return }
        let cursor = append ? nextCursor : nil
        isLoadingMore = append
        loadTask = Task { [weak self] in
            let result = await repository.tickets(afterNumber: cursor, limit: 25)
            guard !Task.isCancelled, let self else { return }
            self.loadTask = nil
            self.isLoadingMore = false
            switch result {
            case .failed:
                if !append { self.listState = .failed }
            case .loaded(let page):
                let existing: [OpenTicket]
                if append, case .loaded(let current) = self.listState { existing = current } else { existing = [] }
                var byNumber = Dictionary(uniqueKeysWithValues: existing.map { ($0.number, $0) })
                page.tickets.forEach { byNumber[$0.number] = $0 }
                self.listState = .loaded(byNumber.values.sorted { $0.number > $1.number })
                self.nextCursor = page.nextCursor
            }
        }
    }

    func plusOne(issueNumber: Int) async {
        var state = interactions[issueNumber] ?? TicketInteractionState()
        guard state.canPlusOne, let repository else { return }
        state.submitting = .plusOne
        state.error = nil
        interactions[issueNumber] = state
        let outcome = await repository.interact(
            issueNumber: issueNumber,
            type: .plusOne,
            text: nil,
            clientId: Self.clientId()
        )
        finish(issueNumber: issueNumber, type: .plusOne, outcome: outcome)
    }

    func comment(issueNumber: Int, text: String) async {
        var state = interactions[issueNumber] ?? TicketInteractionState()
        guard state.canComment, let repository else { return }
        let bounded = TicketComments.bound(text)
        guard !bounded.isEmpty else {
            state.error = .emptyComment
            interactions[issueNumber] = state
            return
        }
        state.submitting = .comment
        state.error = nil
        interactions[issueNumber] = state
        let outcome = await repository.interact(
            issueNumber: issueNumber,
            type: .comment,
            text: bounded,
            clientId: Self.clientId()
        )
        finish(issueNumber: issueNumber, type: .comment, outcome: outcome)
    }

    func clearError(issueNumber: Int) {
        interactions[issueNumber]?.error = nil
    }

    private func finish(
        issueNumber: Int,
        type: TicketInteractionType,
        outcome: TicketInteractionOutcome
    ) {
        var state = interactions[issueNumber] ?? TicketInteractionState()
        state.submitting = nil
        switch outcome {
        case .posted:
            if type == .plusOne { state.plusOneDone = true } else { state.commentDone = true }
            state.error = nil
        case .alreadyDone:
            if type == .plusOne { state.plusOneDone = true } else { state.commentDone = true }
            state.error = .alreadyDone
        case .rateLimited:
            state.error = .rateLimited
        case .failed:
            state.error = .unknown
        }
        interactions[issueNumber] = state
    }

    private static func clientId() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }
}
