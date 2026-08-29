import Foundation
import Observation

/// Observable mirror of the repository's ``AuthState`` for SwiftUI — the iOS
/// equivalent of Android's `AppRoot` collecting the repository's `StateFlow`.
/// Pure Swift: constructed with any ``AuthRepository`` (or nil when Firebase
/// is unconfigured), so it is unit-testable with fakes.
@MainActor
@Observable
final class AuthSession {
    private(set) var state: AuthState
    private let repository: AuthRepository?
    @ObservationIgnored private var observation: Task<Void, Never>?

    /// - Parameter repository: nil when Firebase is not configured in this
    ///   build; the state is then permanently ``AuthState/unavailable``.
    init(repository: AuthRepository?) {
        self.repository = repository
        self.state = repository?.authState ?? .unavailable
        if let repository {
            observation = Task { [weak self] in
                for await newState in repository.authStateUpdates() {
                    self?.state = newState
                }
            }
        }
    }

    /// Signs the current session out. Safe no-op when unavailable.
    func signOut() {
        try? repository?.signOut()
    }

    deinit {
        observation?.cancel()
    }
}
