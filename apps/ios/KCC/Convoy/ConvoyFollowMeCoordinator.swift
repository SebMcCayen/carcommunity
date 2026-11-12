import Foundation
import Observation

@MainActor
@Observable
final class ConvoyFollowMeCoordinator {
    typealias ActivationHandler = @MainActor @Sendable () async -> Void

    private(set) var state: ConvoyFollowMeState?
    private(set) var isLeading = false
    private(set) var isToggling = false

    @ObservationIgnored private let repository: ConvoyFollowMeRepository
    @ObservationIgnored private let now: @Sendable () -> Date
    @ObservationIgnored private var activeConvoyId: String?
    @ObservationIgnored private var sessionGeneration: UInt = 0
    @ObservationIgnored private var selfUid: String?
    @ObservationIgnored private var acceptedMemberUids: Set<String> = []
    @ObservationIgnored private var positions: [String: ConvoyMemberPosition] = [:]
    @ObservationIgnored private weak var surface: StubMapSurface?
    @ObservationIgnored private var publisher = FollowMeTrailPublisher()
    @ObservationIgnored private var observationTask: Task<Void, Never>?
    @ObservationIgnored private var staleTask: Task<Void, Never>?
    @ObservationIgnored private var writeTask: Task<Void, Never>?
    @ObservationIgnored private var pendingPolyline: String?
    @ObservationIgnored private var writerGeneration: UInt = 0

    init(
        repository: ConvoyFollowMeRepository,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.repository = repository
        self.now = now
    }

    func sync(
        convoy: ConvoyItem?,
        currentUid: String?,
        positions: [String: ConvoyMemberPosition],
        surface: StubMapSurface
    ) {
        self.surface = surface
        self.positions = positions
        acceptedMemberUids = Set(convoy?.acceptedMembers.map(\.uid) ?? [])
        let convoyId = convoy?.convoyId
        let identityChanged = convoyId != activeConvoyId || currentUid != selfUid
        selfUid = currentUid
        guard identityChanged else {
            reconcile()
            return
        }

        cancelTasks()
        sessionGeneration &+= 1
        activeConvoyId = convoyId
        state = nil
        isLeading = false
        isToggling = false
        publisher.reset()
        surface.setFollowMeTrail(nil)
        guard let convoyId, currentUid != nil else { return }

        let stream = repository.states(convoyId: convoyId)
        observationTask = Task { [weak self] in
            for await nextState in stream {
                guard !Task.isCancelled, let self, self.activeConvoyId == convoyId else { return }
                self.apply(nextState)
            }
        }
    }

    /// Returns the server's resulting leadership state, or nil on failure or
    /// when the visible convoy session changes during the request. An activation
    /// announcement runs only after the server confirms this member leads.
    func setLeading(
        _ active: Bool,
        onActivated: ActivationHandler? = nil
    ) async -> Bool? {
        guard let convoyId = activeConvoyId, !isToggling else { return nil }
        let requestGeneration = sessionGeneration
        isToggling = true
        let result = await repository.setFollowMe(convoyId: convoyId, active: active)
        guard activeConvoyId == convoyId, sessionGeneration == requestGeneration else { return nil }
        if active, result == true {
            await onActivated?()
        }
        // Keep the control locked until the activation announcement has
        // completed. Otherwise a second tap can deactivate the trail while the
        // first tap is still publishing "Follow me", leaving the convoy with a
        // stale announcement for an inactive trail. A session can change while
        // the callback is suspended, so do not let this completion unlock a
        // newer session's request.
        guard activeConvoyId == convoyId, sessionGeneration == requestGeneration else { return nil }
        isToggling = false
        return result
    }

    func stop() {
        cancelTasks()
        sessionGeneration &+= 1
        activeConvoyId = nil
        selfUid = nil
        state = nil
        isLeading = false
        isToggling = false
        publisher.reset()
        surface?.setFollowMeTrail(nil)
        surface = nil
    }

    private func apply(_ nextState: ConvoyFollowMeState?) {
        let activationChanged = state?.leaderUid != nextState?.leaderUid
            || state?.activationId != nextState?.activationId
        if activationChanged {
            resetWriter()
            publisher.reset()
        }
        state = nextState
        isLeading = FollowMeTrail.isSelfLeading(
            leaderUid: nextState?.leaderUid,
            selfUid: selfUid
        )
        armStaleRecheckIfNeeded()
        reconcile()
    }

    private func reconcile() {
        guard let surface else { return }
        let viewerIsMember = selfUid.map(acceptedMemberUids.contains) == true
        let wasLeading = isLeading
        isLeading = viewerIsMember && FollowMeTrail.isSelfLeading(leaderUid: state?.leaderUid, selfUid: selfUid)
        if wasLeading && !isLeading { resetWriter(); publisher.reset() }
        let leaderUid = state?.leaderUid
        let positionFreshness = leaderUid.flatMap { positions[$0]?.updatedAt }
        let lastFreshAt = [positionFreshness, state?.updatedAt].compactMap { $0 }.max()
        let shouldDraw = viewerIsMember && FollowMeTrail.shouldDraw(
            leaderUid: leaderUid,
            selfUid: selfUid,
            leaderIsMember: leaderUid.map(acceptedMemberUids.contains) ?? false,
            lastFreshAt: lastFreshAt,
            now: now()
        )
        let decoded = shouldDraw ? FollowMeTrail.decode(state?.polyline) : []
        surface.setFollowMeTrail(decoded.count >= 2 ? decoded : nil)

        guard isLeading,
              let convoyId = activeConvoyId,
              let selfUid,
              let position = positions[selfUid],
              let polyline = publisher.ingest(
                MapPoint(longitude: position.longitude, latitude: position.latitude),
                now: now()
              )
        else { return }
        pendingPolyline = polyline
        flushPendingTrail(convoyId: convoyId)
    }

    /// Keep recording geometry during a slow write, then send the latest full
    /// snapshot in order. An old SDK request cannot overwrite a newer activation.
    private func flushPendingTrail(convoyId: String) {
        guard writeTask == nil, let polyline = pendingPolyline else { return }
        pendingPolyline = nil
        let activationId = state?.activationId
        let generation = writerGeneration
        writeTask = Task { [weak self, repository] in
            _ = await repository.writeTrail(convoyId: convoyId, polyline: polyline, activationId: activationId)
            guard !Task.isCancelled, let self, self.activeConvoyId == convoyId,
                  self.writerGeneration == generation else { return }
            self.writeTask = nil
            self.flushPendingTrail(convoyId: convoyId)
        }
    }

    private func resetWriter() {
        writerGeneration &+= 1
        writeTask?.cancel()
        writeTask = nil
        pendingPolyline = nil
    }

    private func armStaleRecheckIfNeeded() {
        staleTask?.cancel()
        staleTask = nil
        guard state?.leaderUid != nil else { return }
        staleTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: FollowMeTrail.staleRecheck)
                } catch {
                    return
                }
                guard !Task.isCancelled else { return }
                self?.reconcile()
            }
        }
    }

    private func cancelTasks() {
        observationTask?.cancel()
        staleTask?.cancel()
        resetWriter()
        observationTask = nil
        staleTask = nil
    }
}
