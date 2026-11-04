import Foundation
import Observation

@MainActor @Observable
final class ClubsCoordinator {
    private let repository: (any ClubsRepository)?
    let friendsRepository: (any FriendsRepository)?
    private(set) var sentInvitations: [SentClubInvitation] = []
    private(set) var invitationsCursor: String?
    private(set) var loadingInvitations = false
    private(set) var invitationsFailed = false
    private var invitationsGeneration = 0
    var canManageInvitations: Bool { enabled && state?.club != nil && ["owner", "moderator"].contains(state?.role ?? "") }
    private(set) var enabled = false
    private(set) var state: CommunityClubState?
    private(set) var clubs: [CommunityClub] = []
    private(set) var nextCursor: String?
    private(set) var loading = false
    private(set) var working = false
    private(set) var failed = false
    private(set) var selectedClub: CommunityClub?
    @ObservationIgnored nonisolated(unsafe) private var subscription: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var reloadTask: Task<Void, Never>?
    private var generation = 0
    private var pendingAction: String?
    private var pendingFields: [String: String] = [:]
    private var pendingOperationID = UUID().uuidString

    init(repository: (any ClubsRepository)?, friendsRepository: (any FriendsRepository)? = nil) {
        self.repository = repository; self.friendsRepository = friendsRepository
    }
    deinit { subscription?.cancel(); reloadTask?.cancel() }
    func start() {
        guard subscription == nil, let repository else { return }
        subscription = Task { [weak self] in
            for await enabled in repository.enabledUpdates() {
                guard let self, !Task.isCancelled else { return }
                guard enabled != self.enabled else { continue }
                self.enabled = enabled
                self.generation += 1
                self.loading = false; self.working = false
                self.state = nil; self.clubs = []; self.selectedClub = nil; self.nextCursor = nil
                self.clearSentInvitations()
                self.reloadTask?.cancel()
                if enabled { self.reloadTask = Task { [weak self] in await self?.reload() } }
            }
        }
    }
    func stop() {
        subscription?.cancel(); subscription = nil
        reloadTask?.cancel(); reloadTask = nil
        generation += 1; enabled = false; state = nil; clubs = []; selectedClub = nil
        loading = false; working = false; nextCursor = nil
        clearSentInvitations()
    }
    func reload(append: Bool = false) async {
        guard enabled, let repository, !loading else { return }
        let generation = generation
        let cursor = append ? nextCursor : nil
        guard !append || cursor != nil else { return }
        loading = true
        defer { if generation == self.generation { loading = false } }
        do {
            // Both reads are server callables. An offline club account or a
            // cached membership is never treated as authorization.
            let state = try await repository.state()
            guard enabled, generation == self.generation, !Task.isCancelled else { return }
            let page = try await repository.directory(after: cursor)
            try Task.checkCancellation()
            guard enabled, generation == self.generation else { return }
            if self.state?.club?.id != state.club?.id || self.state?.role != state.role { clearSentInvitations() }
            self.state = state
            var seen = Set<String>()
            clubs = ((append ? clubs : []) + page.clubs).filter { seen.insert($0.id).inserted }
            nextCursor = page.nextCursor == cursor ? nil : page.nextCursor
            if selectedClub?.id == state.club?.id { selectedClub = state.club }
            failed = false
        } catch {
            guard generation == self.generation, !Task.isCancelled else { return }
            state = nil // Disable membership-changing actions until a fresh read succeeds.
            clearSentInvitations()
            failed = true
        }
    }
    func open(_ club: CommunityClub) async {
        guard enabled, let repository else { return }
        let generation = generation
        selectedClub = club
        do {
            let fresh = try await repository.get(id: club.id)
            guard enabled, generation == self.generation, !Task.isCancelled,
                  selectedClub?.id == club.id else { return }
            selectedClub = fresh
        } catch {
            guard generation == self.generation, !Task.isCancelled else { return }
            guard selectedClub?.id == club.id else { return }
            selectedClub = nil; failed = true
        }
    }
    func closeDetail() { selectedClub = nil }
    func clearSentInvitations() {
        invitationsGeneration += 1; sentInvitations = []; invitationsCursor = nil
        loadingInvitations = false; invitationsFailed = false
    }
    func loadSentInvitations(append: Bool = false) async {
        guard canManageInvitations, let repository, let clubID = state?.club?.id, !loadingInvitations else { return }
        let generation = generation, invitationGeneration = invitationsGeneration
        let cursor = append ? invitationsCursor : nil
        guard !append || cursor != nil else { return }
        loadingInvitations = true; invitationsFailed = false
        if !append { sentInvitations = []; invitationsCursor = nil }
        defer { if invitationGeneration == invitationsGeneration { loadingInvitations = false } }
        do {
            let page = try await repository.sentInvitations(clubID: clubID, after: cursor)
            guard generation == self.generation, invitationGeneration == invitationsGeneration,
                  canManageInvitations, state?.club?.id == clubID, !Task.isCancelled else { return }
            var seen = Set<String>()
            sentInvitations = ((append ? sentInvitations : []) + page.items).filter { seen.insert($0.id).inserted }
            invitationsCursor = page.nextCursor == cursor ? nil : page.nextCursor
        } catch {
            guard generation == self.generation, invitationGeneration == invitationsGeneration, !Task.isCancelled else { return }
            sentInvitations = []; invitationsCursor = nil; invitationsFailed = true
        }
    }
    @discardableResult func revoke(_ invitation: SentClubInvitation) async -> Bool {
        guard canManageInvitations, let clubID = state?.club?.id, invitation.expiresAt > Date() else { return false }
        let generation = generation
        let result = await manage("revokeInvitation", fields: ["clubId": clubID, "targetUid": invitation.recipientUid, "createdAt": invitation.createdAt])
        guard generation == self.generation, canManageInvitations, state?.club?.id == clubID else { return result }
        if result { await loadSentInvitations() }
        else { sentInvitations = []; invitationsCursor = nil; invitationsFailed = true }
        return result
    }
    @discardableResult func manage(_ action: String, fields: [String: String]) async -> Bool {
        guard enabled, let repository, !working, !loading, state != nil else { return false }
        let generation = generation
        // Retry an ambiguous network failure with the original server-side
        // idempotency key. A different action/form gets a new key.
        if pendingAction != action || pendingFields != fields {
            pendingOperationID = UUID().uuidString; pendingAction = action; pendingFields = fields
        }
        working = true
        defer { if generation == self.generation { working = false } }
        do {
            try await repository.manage(action: action, fields: fields, operationID: pendingOperationID)
            guard enabled, generation == self.generation, !Task.isCancelled else { return false }
            pendingAction = nil; selectedClub = nil
            await reload()
            return true
        } catch {
            guard generation == self.generation, !Task.isCancelled else { return false }
            failed = true
            return false
        }
    }
}
