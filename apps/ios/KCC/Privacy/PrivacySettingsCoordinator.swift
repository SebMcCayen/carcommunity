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
    private var lifecycleGeneration = 0

    private(set) var state: PrivacySettingsUiState
    private(set) var draft: PrivacySettingsDraft?
    private(set) var partnerSaveStatus: PrivacySettingsSaveStatus = .idle
    private(set) var leaderboardSaveStatus: PrivacySettingsSaveStatus = .idle
    private var partnerDraftIsDirty = false
    private var leaderboardDraftIsDirty = false

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

    var isAvailable: Bool { repository != nil && uid != nil }

    func start() {
        guard subscription == nil, repository != nil, uid != nil else { return }
        subscribe(resetDraft: true)
    }

    /// Detaches the owner-document listener when the screen leaves the
    /// navigation stack. A later entry starts from a fresh authoritative read
    /// instead of retaining a private-data listener for the whole app session.
    func stop() {
        lifecycleGeneration += 1
        subscription?.cancel()
        subscription = nil
        draft = nil
        partnerSaveStatus = .idle
        leaderboardSaveStatus = .idle
        partnerDraftIsDirty = false
        leaderboardDraftIsDirty = false
        state = (repository == nil || uid == nil) ? .unavailable : .loading
    }

    func reload() {
        guard repository != nil, uid != nil else { return }
        subscribe(resetDraft: true)
    }

    func setPendingPartnerStatsOptIn(_ value: Bool) {
        guard canEdit, case .loaded(let choices) = state else { return }
        draft?.partnerStatsOptIn = value
        partnerDraftIsDirty = value != choices.partnerStatsOptIn
        if partnerSaveStatus != .saving { partnerSaveStatus = .idle }
    }

    func setPendingLeaderboardShown(_ value: Bool) {
        guard canEdit, case .loaded(let choices) = state else { return }
        draft?.leaderboardShown = value
        leaderboardDraftIsDirty = value != choices.leaderboardShown
        if leaderboardSaveStatus != .saving { leaderboardSaveStatus = .idle }
    }

    func savePartnerStats() async {
        guard canEdit, partnerSaveStatus != .saving,
            let repository, let uid, let draft
        else { return }
        let generation = lifecycleGeneration
        let optIn = draft.partnerStatsOptIn
        partnerSaveStatus = .saving
        do {
            try await repository.setPartnerStatsOptIn(uid: uid, optIn: optIn)
            guard generation == lifecycleGeneration else { return }
            partnerDraftIsDirty = false
            if case .loaded(let choices) = state {
                state = .loaded(PrivacySettingsChoices(
                    partnerStatsOptIn: optIn,
                    leaderboardShown: choices.leaderboardShown
                ))
            }
            partnerSaveStatus = .saved
        } catch is CancellationError {
            guard generation == lifecycleGeneration else { return }
            partnerSaveStatus = .idle
        } catch let error as PrivacySettingsWriteError {
            guard generation == lifecycleGeneration else { return }
            partnerSaveStatus = .failed(code: error.code)
        } catch {
            guard generation == lifecycleGeneration else { return }
            partnerSaveStatus = .failed(code: nil)
        }
    }

    func saveLeaderboardVisibility() async {
        guard canEdit, leaderboardSaveStatus != .saving,
            let repository, let uid, let draft
        else { return }
        let generation = lifecycleGeneration
        let optOut = !draft.leaderboardShown
        leaderboardSaveStatus = .saving
        do {
            // The switch models "shown"; Firestore stores the inverse opt-out.
            try await repository.setLeaderboardOptOut(
                uid: uid,
                optOut: optOut
            )
            guard generation == lifecycleGeneration else { return }
            leaderboardDraftIsDirty = false
            if case .loaded(let choices) = state {
                state = .loaded(PrivacySettingsChoices(
                    partnerStatsOptIn: choices.partnerStatsOptIn,
                    leaderboardShown: !optOut
                ))
            }
            leaderboardSaveStatus = .saved
        } catch is CancellationError {
            guard generation == lifecycleGeneration else { return }
            leaderboardSaveStatus = .idle
        } catch let error as PrivacySettingsWriteError {
            guard generation == lifecycleGeneration else { return }
            leaderboardSaveStatus = .failed(code: error.code)
        } catch {
            guard generation == lifecycleGeneration else { return }
            leaderboardSaveStatus = .failed(code: nil)
        }
    }

    private func subscribe(resetDraft: Bool) {
        lifecycleGeneration += 1
        subscription?.cancel()
        if resetDraft {
            draft = nil
            partnerDraftIsDirty = false
            leaderboardDraftIsDirty = false
        }
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
            if draft == nil {
                draft = PrivacySettingsDraft(choices)
            } else {
                if !partnerDraftIsDirty { draft?.partnerStatsOptIn = choices.partnerStatsOptIn }
                if !leaderboardDraftIsDirty { draft?.leaderboardShown = choices.leaderboardShown }
            }
        }
    }
}
