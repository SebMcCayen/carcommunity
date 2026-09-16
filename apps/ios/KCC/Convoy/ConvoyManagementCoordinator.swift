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
    private(set) var actionErrorConvoyId: String?
    private(set) var lastInviteResult: ConvoyInviteResult?
    private(set) var lastLeaveResult: ConvoyLeaveResult?

    var snapshot: ConvoyManagementSnapshot? {
        guard case .loaded(let snapshot) = state else { return nil }
        return snapshot
    }

    var activeConvoy: ConvoyItem? {
        snapshot.flatMap(ConvoyBarLogic.activeConvoy(in:))
    }

    func convoy(id: String) -> ConvoyItem? {
        snapshot?.convoys.first { $0.convoyId == id }
    }

    func actionError(for convoyId: String) -> ConvoyActionError? {
        actionErrorConvoyId == convoyId ? actionError : nil
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
        if snapshot == nil { state = .loading }
        let generation = beginListRequest()
        switch await repository.list() {
        case .loaded(let snapshot):
            await publish(snapshot, generation: generation, using: repository)
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

        clearActionError()
        if action == .accept, !snapshot.canJoinAnotherConvoy {
            setActionError(
                snapshot.hasActiveConvoy ? .alreadyInConvoy : .membershipUncertain,
                convoyId: convoyId
            )
            return nil
        }

        supersedeListRequests()
        busyConvoyIds.insert(convoyId)
        defer { busyConvoyIds.remove(convoyId) }
        switch await repository.respond(convoyId: convoyId, action: action) {
        case .updated(let convoy):
            guard !Task.isCancelled else { return nil }
            applyUpdatedConvoy(convoy)
            await refreshAfterMutation(using: repository)
            return action == .accept ? convoy : nil
        case .failed(.unresolvedPrecondition):
            guard !Task.isCancelled else { return nil }
            await resolvePrecondition(action: action, convoyId: convoyId, using: repository)
            return nil
        case .failed(let error):
            guard !Task.isCancelled else { return nil }
            setActionError(error, convoyId: convoyId)
            return nil
        }
    }

    func clearActionError() {
        actionError = nil
        actionErrorConvoyId = nil
    }

    /// Refreshes without replacing a rendered list/bar with a loading spinner.
    /// Used by the map surface while a convoy is active.
    func refresh() async -> Bool {
        guard let repository else { return false }
        let generation = beginListRequest()
        switch await repository.list() {
        case .loaded(let snapshot):
            guard requestIsCurrent(generation) else { return false }
            await publish(snapshot, generation: generation, using: repository)
            return true
        case .failed:
            // A background refresh must not tear down known-good driving UI.
            // Explicit list loads still surface their failure through `load()`.
            return false
        }
    }

    func observeConvoy(id convoyId: String) async {
        guard let repository else { return }
        while !Task.isCancelled {
            do {
                for try await convoy in repository.observeConvoy(convoyId: convoyId) {
                    guard !Task.isCancelled else { return }
                    if let convoy {
                        let hydrated = await repository.hydrate(convoy)
                        guard !Task.isCancelled else { return }
                        applyUpdatedConvoy(hydrated)
                    } else {
                        removeConvoy(id: convoyId)
                    }
                }
                return
            } catch {
                guard !Task.isCancelled else { return }
                _ = await refresh()
                try? await Task.sleep(for: .seconds(3))
            }
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

        clearActionError()
        lastLeaveResult = nil
        supersedeListRequests()
        busyConvoyIds.insert(convoyId)
        defer { busyConvoyIds.remove(convoyId) }
        switch await repository.lifecycle(convoyId: convoyId, action: action) {
        case .updated(let convoy):
            guard !Task.isCancelled else { return false }
            applyUpdatedConvoy(convoy)
            await refreshAfterMutation(using: repository)
            return true
        case .left(let result):
            guard !Task.isCancelled else { return false }
            lastLeaveResult = result
            removeConvoy(id: convoyId)
            await refreshAfterMutation(using: repository)
            return true
        case .failed(let error):
            guard !Task.isCancelled else { return false }
            setActionError(error, convoyId: convoyId)
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
            if unique.isEmpty { setActionError(.noInvitees, convoyId: convoyId) }
            else if unique.count > ConvoyBarLogic.maximumInviteBatchSize {
                setActionError(.invalid, convoyId: convoyId)
            }
            return false
        }

        clearActionError()
        lastInviteResult = nil
        supersedeListRequests()
        busyConvoyIds.insert(convoyId)
        defer { busyConvoyIds.remove(convoyId) }
        switch await repository.invite(convoyId: convoyId, inviteeUids: unique) {
        case .completed(let result):
            guard !Task.isCancelled else { return false }
            lastInviteResult = result
            applyUpdatedConvoy(result.convoy)
            await refreshAfterMutation(using: repository)
            return true
        case .failed(let error):
            guard !Task.isCancelled else { return false }
            setActionError(error, convoyId: convoyId)
            if error == .notFound || error == .unresolvedPrecondition {
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
            await publish(snapshot, generation: generation, using: repository)
        case .failed(let error):
            guard requestIsCurrent(generation) else { return }
            if snapshot == nil { state = .failed(error) }
        }
    }

    private func applyUpdatedConvoy(_ convoy: ConvoyItem) {
        guard let snapshot else { return }
        var convoys = snapshot.convoys
        guard let index = convoys.firstIndex(where: { $0.convoyId == convoy.convoyId }) else {
            return
        }
        supersedeListRequests()
        convoys[index] = convoy
        state = .loaded(
            ConvoyManagementSnapshot(
                convoys: convoys,
                pendingInvites: snapshot.pendingInvites.filter { $0.convoyId != convoy.convoyId },
                isExhaustive: snapshot.isExhaustive
            )
        )
    }

    private func removeConvoy(id: String) {
        guard let snapshot else { return }
        supersedeListRequests()
        state = .loaded(
            ConvoyManagementSnapshot(
                convoys: snapshot.convoys.filter { $0.convoyId != id },
                pendingInvites: snapshot.pendingInvites.filter { $0.convoyId != id },
                isExhaustive: snapshot.isExhaustive
            )
        )
    }

    private func resolvePrecondition(
        action: ConvoyAction,
        convoyId: String,
        using repository: ConvoyManagementRepository
    ) async {
        let generation = beginListRequest()
        switch await repository.list() {
        case .loaded(let snapshot):
            guard requestIsCurrent(generation) else { return }
            let resolved = snapshot.preservingKnownActive(from: self.snapshot)
            state = .loaded(resolved)
            setActionError(
                action == .accept && resolved.hasActiveConvoy ? .alreadyInConvoy
                    : (action == .decline || resolved.isExhaustive
                        ? .inviteGone : .membershipUncertain),
                convoyId: convoyId
            )
            await publish(resolved, generation: generation, using: repository)
        case .failed:
            guard requestIsCurrent(generation) else { return }
            setActionError(.generic, convoyId: convoyId)
        }
    }

    private func setActionError(_ error: ConvoyActionError, convoyId: String) {
        actionError = error
        actionErrorConvoyId = convoyId
    }

    func recordCreatedConvoy(_ convoy: ConvoyItem) {
        supersedeListRequests()
        let previous = snapshot
        let convoys = [convoy] + (previous?.convoys ?? []).filter {
            $0.convoyId != convoy.convoyId
        }
        state = .loaded(ConvoyManagementSnapshot(
            convoys: convoys,
            pendingInvites: previous?.pendingInvites ?? [],
            isExhaustive: previous?.isExhaustive ?? false
        ))
    }

    private func publish(
        _ incoming: ConvoyManagementSnapshot,
        generation: Int,
        using repository: ConvoyManagementRepository
    ) async {
        guard requestIsCurrent(generation) else { return }
        let merged = incoming.preservingKnownActive(from: snapshot)
        // The callable snapshot drives the bar immediately; profiles are cosmetic.
        state = .loaded(merged)
        let hydrated = await repository.hydrateSnapshot(merged)
        guard requestIsCurrent(generation) else { return }
        state = .loaded(hydrated)
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
