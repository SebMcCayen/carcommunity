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
    private let clearSavedPlaces: (String) -> Bool
    @ObservationIgnored private var observation: Task<Void, Never>?

    /// - Parameter repository: nil when Firebase is not configured in this
    ///   build; the state is then permanently ``AuthState/unavailable``.
    convenience init(
        repository: AuthRepository?,
        clearSavedPlaces: @escaping (String) -> Bool = {
            ProtectedFileSavedPlacesStore(uid: $0).clear()
        }
    ) {
        self.init(observing: repository, onIdentityChange: { _ in }, clearSavedPlaces: clearSavedPlaces)
    }

    init(
        observing repository: AuthRepository?,
        onIdentityChange: @escaping (String?) -> Void,
        clearSavedPlaces: @escaping (String) -> Bool = {
            ProtectedFileSavedPlacesStore(uid: $0).clear()
        }
    ) {
        self.repository = repository
        self.clearSavedPlaces = clearSavedPlaces
        self.state = repository?.authState ?? .unavailable
        onIdentityChange(Self.uid(in: state))
        if let repository {
            observation = Task { [weak self] in
                for await newState in repository.authStateUpdates() {
                    self?.state = newState
                    onIdentityChange(Self.uid(in: newState))
                }
            }
        }
    }

    /// Signs the current session out and reports whether local credentials
    /// were cleared. Callers must keep recovery UI available on failure.
    @discardableResult
    func signOut() -> Bool {
        guard let repository else {
            if case .signedIn = state { return false }
            return true
        }
        if case .signedIn(let uid, _) = state,
           !clearSavedPlaces(uid) {
            return false
        }
        do {
            try repository.signOut()
            return true
        } catch {
            return false
        }
    }

    /// Clears the session only when it still belongs to the operation's
    /// originating identity. Irreversible requests may complete after their
    /// screen has disappeared; they must never sign out a replacement account
    /// that authenticated while the request was in flight.
    @discardableResult
    func signOut(ifSignedInAs expectedUid: String) -> Bool {
        guard let repository else { return true }
        if case .signedIn(let currentUid, _) = state,
           currentUid != expectedUid { return true }
        guard clearSavedPlaces(expectedUid) else { return false }
        do {
            try repository.signOut(ifCurrentUidIs: expectedUid)
            return true
        } catch {
            return false
        }
    }

    private static func uid(in state: AuthState) -> String? {
        if case .signedIn(let uid, _) = state { return uid }
        return nil
    }

    deinit {
        observation?.cancel()
    }
}
