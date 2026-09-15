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
    private var listRequestGeneration = 0

    private(set) var state: ConvoyManagementState = .loading
    private(set) var busyConvoyIds = Set<String>()
    private(set) var actionError: ConvoyActionError?
    private(set) var lastInviteResult: ConvoyInviteResult?
    private(set) var lastLeaveResult: ConvoyLeaveResult?

    var snapshot: ConvoyManagementSnapshot? {
        guard case .loaded(let snapshot) = state else { return nil }
        return snapshot
    }

    var activeConvoy: ConvoyItem? {
        snapshot.flatMap(ConvoyBarLogic.activeConvoy(in:))
    }

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
        let generation = beginListRequest()
        switch await repository.list() {
        case .loaded(let snapshot):
            guard requestIsCurrent(generation) else { return }
            state = .loaded(snapshot)
        case .failed(let error):
            guard requestIsCurrent(generation) else { return }
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

        supersedeListRequests()
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

    /// Refreshes without replacing a rendered list/bar with a loading spinner.
    /// Used by the map surface while a convoy is active.
    func refresh() async -> Bool {
        guard let repository else { return false }
        let generation = beginListRequest()
        switch await repository.list() {
        case .loaded(let snapshot):
            guard requestIsCurrent(generation) else { return false }
            state = .loaded(snapshot)
            return true
        case .failed:
            // A background refresh must not tear down known-good driving UI.
            // Explicit list loads still surface their failure through `load()`.
            return false
        }
    }

    @discardableResult
    func runLifecycle(
        convoyId: String,
        action: ConvoyLifecycleAction
    ) async -> Bool {
        guard !convoyId.isEmpty,
              !busyConvoyIds.contains(convoyId),
              let repository
        else { return false }

        actionError = nil
        lastLeaveResult = nil
        supersedeListRequests()
        busyConvoyIds.insert(convoyId)
        defer { busyConvoyIds.remove(convoyId) }
        switch await repository.lifecycle(convoyId: convoyId, action: action) {
        case .updated:
            guard !Task.isCancelled else { return false }
            await refreshAfterMutation(using: repository)
            return true
        case .left(let result):
            guard !Task.isCancelled else { return false }
            lastLeaveResult = result
            await refreshAfterMutation(using: repository)
            return true
        case .failed(let error):
            guard !Task.isCancelled else { return false }
            actionError = error
            if error == .notFound
                || error == .alreadyEnded
                || error == .leaveFailed
                || error == .cannotStart
            {
                await refreshAfterMutation(using: repository)
            }
            return false
        }
    }

    @discardableResult
    func invite(convoyId: String, inviteeUids: [String]) async -> Bool {
        let unique = Array(Set(inviteeUids.filter { !$0.isEmpty })).sorted()
        guard !convoyId.isEmpty,
              !unique.isEmpty,
              unique.count <= ConvoyBarLogic.maximumInviteBatchSize,
              !busyConvoyIds.contains(convoyId),
              let repository
        else {
            if unique.isEmpty { actionError = .noInvitees }
            else if unique.count > ConvoyBarLogic.maximumInviteBatchSize { actionError = .invalid }
            return false
        }

        actionError = nil
        lastInviteResult = nil
        supersedeListRequests()
        busyConvoyIds.insert(convoyId)
        defer { busyConvoyIds.remove(convoyId) }
        switch await repository.invite(convoyId: convoyId, inviteeUids: unique) {
        case .completed(let result):
            guard !Task.isCancelled else { return false }
            lastInviteResult = result
            await refreshAfterMutation(using: repository)
            return true
        case .failed(let error):
            guard !Task.isCancelled else { return false }
            actionError = error
            if error == .notFound || error == .noInvitees {
                await refreshAfterMutation(using: repository)
            }
            return false
        }
    }

    func clearInviteResult() {
        lastInviteResult = nil
    }

    func clearLeaveResult() {
        lastLeaveResult = nil
    }

    private func refreshAfterMutation(using repository: ConvoyManagementRepository) async {
        let generation = beginListRequest()
        switch await repository.list() {
        case .loaded(let snapshot):
            guard requestIsCurrent(generation) else { return }
            state = .loaded(snapshot)
        case .failed(let error):
            guard requestIsCurrent(generation) else { return }
            if snapshot == nil { state = .failed(error) }
        }
    }

    private func resolvePrecondition(
        action: ConvoyAction,
        using repository: ConvoyManagementRepository
    ) async {
        let generation = beginListRequest()
        switch await repository.list() {
        case .loaded(let snapshot):
            guard requestIsCurrent(generation) else { return }
            state = .loaded(snapshot)
            actionError = action == .accept && snapshot.hasActiveConvoy
                ? .alreadyInConvoy
                : .inviteGone
        case .failed:
            guard requestIsCurrent(generation) else { return }
            actionError = .generic
        }
    }

    private func beginListRequest() -> Int {
        listRequestGeneration += 1
        return listRequestGeneration
    }

    private func supersedeListRequests() {
        listRequestGeneration += 1
    }

    private func requestIsCurrent(_ generation: Int) -> Bool {
        !Task.isCancelled && generation == listRequestGeneration
    }
}
