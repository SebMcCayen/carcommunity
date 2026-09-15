import Foundation
import Observation

enum ConvoyManagementState: Equatable, Sendable {
    case loading
    case unavailable
    case failed(ConvoyActionError)
    case loaded(ConvoyManagementSnapshot)
}

@MainActor
@Observable
final class ConvoyManagementCoordinator {
    private let repository: ConvoyManagementRepository?

    private(set) var state: ConvoyManagementState = .loading
    private(set) var busyConvoyIds = Set<String>()
    private(set) var actionError: ConvoyActionError?

    init(repository: ConvoyManagementRepository?) {
        self.repository = repository
        if repository == nil { state = .unavailable }
    }

    func load() async {
        guard let repository else {
            state = .unavailable
            return
        }
        state = .loading
        switch await repository.list() {
        case .loaded(let snapshot):
            guard !Task.isCancelled else { return }
            state = .loaded(snapshot)
        case .failed(let error):
            guard !Task.isCancelled else { return }
            state = .failed(error)
        }
    }

    @discardableResult
    func respond(convoyId: String, action: ConvoyAction) async -> ConvoyItem? {
        guard !convoyId.isEmpty,
              !busyConvoyIds.contains(convoyId),
              let repository,
              case .loaded(let snapshot) = state
        else { return nil }

        actionError = nil
        if action == .accept, !snapshot.canJoinAnotherConvoy {
            actionError = snapshot.hasActiveConvoy ? .alreadyInConvoy : .generic
            return nil
        }

        busyConvoyIds.insert(convoyId)
        defer { busyConvoyIds.remove(convoyId) }
        switch await repository.respond(convoyId: convoyId, action: action) {
        case .updated(let convoy):
            guard !Task.isCancelled else { return nil }
            await refreshAfterMutation(using: repository)
            return action == .accept ? convoy : nil
        case .failed(.unresolvedPrecondition):
            guard !Task.isCancelled else { return nil }
            await resolvePrecondition(action: action, using: repository)
            return nil
        case .failed(let error):
            guard !Task.isCancelled else { return nil }
            actionError = error
            return nil
        }
    }

    func clearActionError() {
        actionError = nil
    }

    private func refreshAfterMutation(using repository: ConvoyManagementRepository) async {
        switch await repository.list() {
        case .loaded(let snapshot):
            guard !Task.isCancelled else { return }
            state = .loaded(snapshot)
        case .failed(let error):
            guard !Task.isCancelled else { return }
            state = .failed(error)
        }
    }

    private func resolvePrecondition(
        action: ConvoyAction,
        using repository: ConvoyManagementRepository
    ) async {
        switch await repository.list() {
        case .loaded(let snapshot):
            guard !Task.isCancelled else { return }
            state = .loaded(snapshot)
            actionError = action == .accept && snapshot.hasActiveConvoy
                ? .alreadyInConvoy
                : .inviteGone
        case .failed:
            guard !Task.isCancelled else { return }
            actionError = .generic
        }
    }
}
