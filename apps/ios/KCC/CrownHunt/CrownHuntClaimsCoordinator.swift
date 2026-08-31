import Foundation
import Observation

/// UI-facing state of the member's claim history. Loading/empty/loaded/
/// unavailable/failed, so the SwiftUI list stays a dumb switch.
enum CrownHuntClaimsUiState: Equatable, Sendable {
    case loading
    /// The read succeeded but the member has made no claim attempts yet.
    case empty
    /// The member's own claim attempts, newest first.
    case loaded([CrownHuntClaim])
    /// No repository in this build (config-less), or no signed-in session.
    case unavailable
    case failed(code: String?)
}

/// Orchestrates the read-only claim history: subscribes the repository's
/// one-shot read and folds its emission into ``CrownHuntClaimsUiState``. Pure
/// Swift so it is unit-testable with a fake.
@MainActor
@Observable
final class CrownHuntClaimsCoordinator {
    private let repository: CrownHuntClaimsRepository?
    private let uid: String?
    private let passesMemberGate: Bool

    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    private(set) var state: CrownHuntClaimsUiState = .loading

    init(repository: CrownHuntClaimsRepository?, uid: String?, passesMemberGate: Bool) {
        self.repository = repository
        self.uid = uid
        self.passesMemberGate = passesMemberGate
    }

    deinit {
        subscription?.cancel()
    }

    func start() {
        guard subscription == nil else { return }
        subscribe()
    }

    func reload() {
        subscribe()
    }

    private func subscribe() {
        subscription?.cancel()
        guard let repository, let uid, passesMemberGate else {
            state = .unavailable
            return
        }
        state = .loading
        let stream = repository.claims(uid: uid)
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(snapshot)
            }
        }
    }

    private func apply(_ snapshot: CrownClaimsSnapshot) {
        switch snapshot {
        case .failed(let code):
            state = .failed(code: code)
        case .loaded(let claims):
            state = claims.isEmpty ? .empty : .loaded(claims)
        }
    }
}
