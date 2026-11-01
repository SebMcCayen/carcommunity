import Foundation
import Observation

@MainActor @Observable
final class ClubsCoordinator {
    private let repository: (any ClubsRepository)?
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

    init(repository: (any ClubsRepository)?) { self.repository = repository }
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
            self.state = state
            var seen = Set<String>()
            clubs = ((append ? clubs : []) + page.clubs).filter { seen.insert($0.id).inserted }
            nextCursor = page.nextCursor == cursor ? nil : page.nextCursor
            if selectedClub?.id == state.club?.id { selectedClub = state.club }
            failed = false
        } catch {
            guard generation == self.generation, !Task.isCancelled else { return }
            state = nil // Disable membership-changing actions until a fresh read succeeds.
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
            selectedClub = nil; failed = true
        }
    }
    func closeDetail() { selectedClub = nil }
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
