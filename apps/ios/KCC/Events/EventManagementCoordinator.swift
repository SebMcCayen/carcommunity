import Foundation
import Observation

enum EventFormMode: Equatable, Sendable {
    case create
    case edit(eventId: String)
}

enum EventFormSubmitState: Equatable, Sendable {
    case idle, saving
    case created(eventId: String)
    case updated
    case failedCreate(CreateEventFailure)
    case failedEdit(ManageEventFailure)
}

/// Deterministic create/edit write orchestration. The form owns presentation;
/// this type owns validation, one-write-at-a-time, and callable outcomes.
@MainActor
@Observable
final class EventFormCoordinator {
    private let repository: EventsRepository
    let mode: EventFormMode
    private(set) var state: EventFormSubmitState = .idle
    @ObservationIgnored private var submission: Task<Void, Never>?

    init(repository: EventsRepository, mode: EventFormMode) {
        self.repository = repository
        self.mode = mode
    }

    deinit { submission?.cancel() }

    func submit(_ input: EventFormInput) {
        guard state != .saving else { return }
        guard Events.valid(input) else {
            switch mode {
            case .create: state = .failedCreate(.unknown)
            case .edit: state = .failedEdit(.unknown)
            }
            return
        }
        state = .saving
        submission = Task { [weak self, repository, mode] in
            do {
                switch mode {
                case .create:
                    let id = try await repository.createEvent(input)
                    guard !Task.isCancelled, let self else { return }
                    self.state = .created(eventId: id)
                case .edit(let eventId):
                    try await repository.updateEvent(eventId: eventId, input: input)
                    guard !Task.isCancelled, let self else { return }
                    self.state = .updated
                }
            } catch is CancellationError {
                guard let self else { return }
                self.state = .idle
            } catch let error as CreateEventError {
                guard !Task.isCancelled, let self else { return }
                self.state = .failedCreate(error.reason)
            } catch let error as ManageEventError {
                guard !Task.isCancelled, let self else { return }
                self.state = .failedEdit(error.reason)
            } catch {
                guard !Task.isCancelled, let self else { return }
                switch mode {
                case .create: self.state = .failedCreate(.unknown)
                case .edit: self.state = .failedEdit(.unknown)
                }
            }
        }
    }

    func resetFailure() {
        switch state {
        case .failedCreate, .failedEdit: state = .idle
        default: break
        }
    }
}

enum EventAttendeesState: Equatable, Sendable {
    case idle, loading
    case loaded([EventAttendee])
    case requiresPaid, unavailable, failed
}

enum EventManageState: Equatable, Sendable {
    case idle, deleting, deleted
    case failed(ManageEventFailure)
}

enum EventCheckInState: Equatable, Sendable {
    case idle, working, recorded, verified
    case failed(EventCheckInFailure)
}

enum EventCheckInFailure: Equatable, Sendable {
    case windowClosed, positionUnavailable, mockLocation, outsideGeofence, notCheckinable, generic
}
