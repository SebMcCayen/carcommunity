import Foundation
import Observation

/// Owns the privacy listener, pending edits, and independent single-flight
/// saves for the two settings. No Firebase or SwiftUI types, so the privacy
/// state machine is fully covered by XCTest.
@MainActor
@Observable
final class PrivacySettingsCoordinator {
    private let repository: PrivacySettingsRepository?
    private let uid: String?
    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    private(set) var state: PrivacySettingsUiState
    private(set) var draft: PrivacySettingsDraft?
    private(set) var partnerSaveStatus: PrivacySettingsSaveStatus = .idle
    private(set) var leaderboardSaveStatus: PrivacySettingsSaveStatus = .idle

    init(repository: PrivacySettingsRepository?, uid: String?) {
        self.repository = repository
        self.uid = uid
        state = (repository == nil || uid == nil) ? .unavailable : .loading
    }

    deinit { subscription?.cancel() }

    /// A current definitive read is required for editing and saving. This is
    /// stricter than merely having an old draft: if the listener later fails,
    /// controls lock until a new authoritative snapshot arrives.
    var canEdit: Bool {
        guard draft != nil else { return false }
        if case .loaded = state { return true }
        return false
    }

    func start() {
        guard subscription == nil, repository != nil, uid != nil else { return }
        subscribe(resetDraft: false)
    }

    func reload() {
        guard repository != nil, uid != nil else { return }
        subscribe(resetDraft: true)
    }

    func setPendingPartnerStatsOptIn(_ value: Bool) {
        guard canEdit else { return }
        draft?.partnerStatsOptIn = value
        if partnerSaveStatus != .saving { partnerSaveStatus = .idle }
    }

    func setPendingLeaderboardShown(_ value: Bool) {
        guard canEdit else { return }
        draft?.leaderboardShown = value
        if leaderboardSaveStatus != .saving { leaderboardSaveStatus = .idle }
    }

    func savePartnerStats() async {
        guard canEdit, partnerSaveStatus != .saving,
            let repository, let uid, let draft
        else { return }
        partnerSaveStatus = .saving
        do {
            try await repository.setPartnerStatsOptIn(uid: uid, optIn: draft.partnerStatsOptIn)
            partnerSaveStatus = .saved
        } catch is CancellationError {
            partnerSaveStatus = .idle
        } catch let error as PrivacySettingsWriteError {
            partnerSaveStatus = .failed(code: error.code)
        } catch {
            partnerSaveStatus = .failed(code: nil)
        }
    }

    func saveLeaderboardVisibility() async {
        guard canEdit, leaderboardSaveStatus != .saving,
            let repository, let uid, let draft
        else { return }
        leaderboardSaveStatus = .saving
        do {
            // The switch models "shown"; Firestore stores the inverse opt-out.
            try await repository.setLeaderboardOptOut(
                uid: uid,
                optOut: !draft.leaderboardShown
            )
            leaderboardSaveStatus = .saved
        } catch is CancellationError {
            leaderboardSaveStatus = .idle
        } catch let error as PrivacySettingsWriteError {
            leaderboardSaveStatus = .failed(code: error.code)
        } catch {
            leaderboardSaveStatus = .failed(code: nil)
        }
    }

    private func subscribe(resetDraft: Bool) {
        subscription?.cancel()
        if resetDraft { draft = nil }
        state = .loading
        guard let repository, let uid else { return }
        let stream = repository.settings(uid: uid)
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(snapshot)
            }
        }
    }

    private func apply(_ snapshot: PrivacySettingsSnapshot) {
        switch snapshot {
        case .failed(let code):
            state = .failed(code: code)
        case .loaded(let choices):
            state = .loaded(choices)
            if draft == nil { draft = PrivacySettingsDraft(choices) }
        }
    }
}
