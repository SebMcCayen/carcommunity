import Foundation
import Observation

/// UI-facing state of the notification-settings screen — the iOS fold of
/// Android's `NotificationSettingsState`, with the config-less build modelled
/// explicitly (``unavailable``), the same shape as ``GarageUiState``.
enum NotificationSettingsUiState: Equatable, Sendable {
    /// Waiting for the first preferences snapshot.
    case loading
    /// No repository (Firebase unconfigured) or no signed-in uid.
    case unavailable
    /// The owner's preferences (all-enabled when nothing is stored).
    case loaded(NotificationPreferences)
}

/// UI-facing status of a preferences save — Android's
/// `NotificationSettingsSaveStatus`, extended with the failing Firestore
/// status code for diagnostics parity with the events/garage write pattern.
enum NotificationSettingsSaveStatus: Equatable, Sendable {
    case idle
    case saving
    case saved
    /// The write failed. `code` is the bare Firestore status name when one was
    /// available (``NotificationSettingsWriteError/code``).
    case failed(code: String?)
}

/// Orchestrates notification settings: subscribes the owner's preferences
/// stream, folds it into ``NotificationSettingsUiState``, and drives a
/// toggle → save (single-flight). Pure Swift (no Firebase/SwiftUI types) so it
/// is unit-testable with a fake repository — the iOS counterpart of Android's
/// `NotificationSettingsCoordinator` + `NotificationSettingsRoute`.
///
/// A toggle recomputes from the CURRENT loaded snapshot and saves; the Switch
/// reflects the observed (server-truth) preferences, so a refused save leaves
/// no optimistic flip asserting a preference that was never persisted — the
/// same "derive from the server's latest answer" rule Android follows.
@MainActor
@Observable
final class NotificationSettingsCoordinator {
    private let repository: NotificationSettingsRepository?
    private let uid: String?
    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    private(set) var state: NotificationSettingsUiState
    private(set) var saveStatus: NotificationSettingsSaveStatus = .idle

    init(repository: NotificationSettingsRepository?, uid: String?) {
        self.repository = repository
        self.uid = uid
        self.state = (repository == nil || uid == nil) ? .unavailable : .loading
    }

    deinit {
        subscription?.cancel()
    }

    /// Begins observing on first appearance. Idempotent. No-op when
    /// unavailable.
    func start() {
        guard subscription == nil, repository != nil, uid != nil else { return }
        subscribe()
    }

    /// The "try again" affordance — re-subscribes from scratch. No-op when
    /// unavailable.
    func reload() {
        guard repository != nil, uid != nil else { return }
        subscribe()
    }

    /// Recomputes the preferences with one channel toggled and saves them —
    /// Android's `onToggle` in `NotificationSettingsRoute`.
    ///
    /// Only acts on a LOADED snapshot and when no save is in flight: a toggle
    /// during loading would persist all-enabled over the user's real
    /// preferences, and a toggle during a save is a no-op the single-flight
    /// gate would drop anyway. Essential categories reject the toggle in
    /// ``NotificationPreferences/withToggle(_:channel:enabled:)``.
    func toggle(_ category: NotificationCategory, channel: NotificationChannel, enabled: Bool) async {
        guard case .loaded(let preferences) = state,
            saveStatus != .saving,
            let repository,
            let uid
        else { return }
        let updated = preferences.withToggle(category, channel: channel, enabled: enabled)
        // A no-op recompute — an essential category (rejected by withToggle) or
        // a toggle to the value already held — is not worth a round trip.
        guard updated != preferences else { return }
        saveStatus = .saving
        do {
            try await repository.savePreferences(uid: uid, preferences: updated)
            saveStatus = .saved
        } catch is CancellationError {
            saveStatus = .idle
        } catch let error as NotificationSettingsWriteError {
            saveStatus = .failed(code: error.code)
        } catch {
            saveStatus = .failed(code: nil)
        }
    }

    /// Returns ``saveStatus`` to idle — called when the screen dismisses a
    /// failure notice or on back (Android's `reset`). A SAVING status is
    /// deliberately not resettable, so it cannot defeat the single-flight gate.
    func resetSaveStatus() {
        guard saveStatus != .saving else { return }
        saveStatus = .idle
    }

    private func subscribe() {
        subscription?.cancel()
        state = .loading
        guard let repository, let uid else { return }
        let stream = repository.preferences(uid: uid)
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(snapshot)
            }
        }
    }

    private func apply(_ snapshot: NotificationSettingsSnapshot) {
        switch snapshot {
        case .loaded(let preferences):
            state = .loaded(preferences)
        }
    }
}
