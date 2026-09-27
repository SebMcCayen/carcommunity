import Foundation
import Observation

/// Owns the blocked-list subscription and serializes block mutations. List
/// changes remain server-driven: a successful callable is not reflected until
/// the authoritative owner listener emits it.
@MainActor
@Observable
final class BlockingCoordinator {
    private let repository: BlockingRepository?
    private let uid: String?
    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    private(set) var state: BlockedUsersUiState
    private(set) var actionStatus: BlockActionStatus = .idle

    init(repository: BlockingRepository?, uid: String?) {
        self.repository = repository
        self.uid = uid
        state = repository == nil || uid == nil ? .unavailable : .loading
    }

    deinit { subscription?.cancel() }

    func start() {
        guard subscription == nil, let repository, let uid else { return }
        let stream = repository.observeBlocked(uid: uid)
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled else { return }
                self?.apply(snapshot)
            }
        }
    }

    func reload() {
        guard repository != nil, uid != nil else { return }
        subscription?.cancel()
        subscription = nil
        state = .loading
        start()
    }

    func block(targetUserId: String) async {
        await execute(targetUserId: targetUserId) { repository, target in
            try await repository.block(targetUserId: target)
        }
    }

    func unblock(targetUserId: String) async {
        await execute(targetUserId: targetUserId) { repository, target in
            try await repository.unblock(targetUserId: target)
        }
    }

    func resetActionStatus() {
        guard !actionStatus.isWorking else { return }
        actionStatus = .idle
    }

    private func execute(
        targetUserId: String,
        operation: @escaping (BlockingRepository, String) async throws -> Void
    ) async {
        guard !actionStatus.isWorking, let repository else { return }
        let target = targetUserId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty, target != uid else {
            actionStatus = .failed(code: .invalidArgument)
            return
        }
        actionStatus = .working(targetUserId: target)
        do {
            try await operation(repository, target)
            guard !Task.isCancelled else {
                actionStatus = .idle
                return
            }
            actionStatus = .succeeded
        } catch is CancellationError {
            actionStatus = .idle
        } catch let error as KccFunctionsError {
            // Only the stable contract code survives. In particular, no
            // direction-specific server message reaches the UI.
            actionStatus = .failed(code: error.code)
        } catch {
            actionStatus = .failed(code: nil)
        }
    }

    private func apply(_ snapshot: BlockedUsersSnapshot) {
        switch snapshot {
        case .loaded(let users): state = users.isEmpty ? .empty : .loaded(users)
        case .failed(let code): state = .failed(code: code)
        }
    }
}
