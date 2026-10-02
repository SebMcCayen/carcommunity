import Foundation
import Observation

@MainActor
@Observable
final class PointsCoordinator {
    private let repository: PointsRepository?
    private let uid: String?

    @ObservationIgnored
    nonisolated(unsafe) private var balanceTask: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var entriesTask: Task<Void, Never>?

    private(set) var balance: Int64?
    private(set) var entriesState: PointsEntriesUiState

    init(repository: PointsRepository?, uid: String?) {
        self.repository = repository
        self.uid = uid
        entriesState = (repository == nil || uid == nil) ? .unavailable : .loading
    }

    deinit {
        balanceTask?.cancel()
        entriesTask?.cancel()
    }

    func start() {
        guard balanceTask == nil, entriesTask == nil else { return }
        subscribe()
    }

    func reload() {
        guard repository != nil, uid != nil else { return }
        subscribe()
    }

    var recentEarnings: [PointsEntry] {
        guard case .loaded(let entries) = entriesState else { return [] }
        return Points.recentEarnings(entries)
    }

    private func subscribe() {
        balanceTask?.cancel()
        entriesTask?.cancel()
        guard let repository, let uid else {
            entriesState = .unavailable
            return
        }
        entriesState = .loading
        let balanceStream = repository.observeBalance(uid: uid)
        balanceTask = Task { [weak self] in
            for await balance in balanceStream {
                guard !Task.isCancelled, let self else { return }
                self.balance = balance
            }
        }
        let entriesStream = repository.observeEntries(uid: uid)
        entriesTask = Task { [weak self] in
            for await snapshot in entriesStream {
                guard !Task.isCancelled, let self else { return }
                switch snapshot {
                case .loaded(let entries): self.entriesState = .loaded(entries)
                case .failed(let code): self.entriesState = .failed(code: code)
                }
            }
        }
    }
}
