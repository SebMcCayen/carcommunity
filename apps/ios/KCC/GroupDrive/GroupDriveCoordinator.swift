import Foundation
import Observation

@MainActor
@Observable
final class GroupDriveCoordinator {
    private let repository: GroupDriveRepository
    let eventId: String
    let uid: String
    let canJoin: Bool

    private(set) var participants: [GroupDriveParticipant] = []
    private(set) var myStatus: GroupDriveStatus?
    private(set) var isLoading = true
    private(set) var loadFailed = false
    private(set) var actionState: GroupDriveActionState = .idle

    @ObservationIgnored
    nonisolated(unsafe) private var subscriptions: [Task<Void, Never>] = []
    @ObservationIgnored
    nonisolated(unsafe) private var actionTask: Task<Void, Never>?

    init(
        repository: GroupDriveRepository,
        eventId: String,
        uid: String,
        canJoin: Bool
    ) {
        self.repository = repository
        self.eventId = eventId
        self.uid = uid
        self.canJoin = canJoin
    }

    deinit {
        subscriptions.forEach { $0.cancel() }
        actionTask?.cancel()
    }

    var activeParticipants: [GroupDriveParticipant] {
        GroupDrive.activeParticipants(participants)
    }

    var activeParticipantUids: [String] { activeParticipants.map(\.uid) }

    var isParticipating: Bool { GroupDrive.isParticipating(myStatus) }

    func start() {
        guard subscriptions.isEmpty else { return }
        isLoading = true
        loadFailed = false
        let rosterStream = repository.participants(eventId: eventId)
        let ownStream = repository.myStatus(eventId: eventId, uid: uid)
        subscriptions = [
            Task { [weak self] in
                for await snapshot in rosterStream {
                    guard !Task.isCancelled, let self else { return }
                    self.apply(roster: snapshot)
                }
            },
            Task { [weak self] in
                for await snapshot in ownStream {
                    guard !Task.isCancelled, let self else { return }
                    self.apply(ownStatus: snapshot)
                }
            },
        ]
    }

    func join() {
        let eventId = eventId
        execute { try await $0.join(eventId: eventId) }
    }

    func updateStatus(_ status: GroupDriveStatus) {
        guard GroupDriveStatus.updatable.contains(status) else { return }
        let eventId = eventId
        execute { try await $0.updateStatus(eventId: eventId, status: status) }
    }

    func leave() {
        let eventId = eventId
        execute { try await $0.leave(eventId: eventId) }
    }

    func clearFailure() {
        if actionState == .failed { actionState = .idle }
    }

    private func execute(
        _ operation: @escaping @Sendable (GroupDriveRepository) async throws -> Void
    ) {
        guard actionState != .working else { return }
        actionTask?.cancel()
        actionState = .working
        let repository = repository
        actionTask = Task { [weak self, repository] in
            do {
                try await operation(repository)
                guard !Task.isCancelled else { return }
                self?.actionState = .idle
            } catch is CancellationError {
                self?.actionState = .idle
            } catch {
                guard !Task.isCancelled else { return }
                self?.actionState = .failed
            }
        }
    }

    private func apply(roster snapshot: GroupDriveRosterSnapshot) {
        isLoading = false
        switch snapshot {
        case .loaded(let participants):
            self.participants = participants
            loadFailed = false
        case .failed:
            participants = []
            loadFailed = true
        }
    }

    private func apply(ownStatus snapshot: GroupDriveOwnStatusSnapshot) {
        isLoading = false
        switch snapshot {
        case .loaded(let status):
            myStatus = status
        case .failed:
            myStatus = nil
            loadFailed = true
        }
    }
}
