import Foundation
import Observation

enum CrownMapClaimStatus: Equatable, Sendable {
    case idle
    case collecting
    case point(CrownPointClaimOutcome)
    case spawn(CrownSpawnClaimOutcome)
    case failed(code: String?)
}

@MainActor
@Observable
final class CrownHuntMapCoordinator {
    private let repository: CrownHuntMapRepository?
    private let locationProvider: any LocationProvider
    private let crownHuntEnabled: Bool
    private let spawnEnabled: Bool
    private let passesMemberGate: Bool
    private let uid: String?
    private let clock: () -> Date

    @ObservationIgnored private var fixTask: Task<Void, Never>?
    @ObservationIgnored private var refreshTask: Task<Void, Never>?
    @ObservationIgnored private var evidenceTask: Task<Void, Never>?
    @ObservationIgnored private var readinessTask: Task<Void, Never>?
    @ObservationIgnored private var evidenceStartedAt: Date?
    @ObservationIgnored private var evidenceRelevant = false
    @ObservationIgnored private var evidenceRetryAt = Date.distantPast
    @ObservationIgnored private var claimIdempotencyKey = UUID().uuidString.lowercased()
    private(set) var readinessState: CrownCollectState = .noPosition
    @ObservationIgnored private var tracker = CrownFixTracker()
    @ObservationIgnored private var lastCellKeys: [String]?
    @ObservationIgnored private var pointsLoaded = false

    private(set) var points: [CrownMapPoint] = []
    private(set) var spawns: [CrownSpawn] = []
    private(set) var latestFix: LocationFix?
    private(set) var loading = false
    private(set) var claimStatus: CrownMapClaimStatus = .idle
    private(set) var collectedSpawnIds: Set<String> = []
    private(set) var reportedPlacements: Set<String> = []
    var selectedTarget: CrownMapTarget? {
        didSet {
            guard selectedTarget?.id != oldValue?.id else { return }
            claimIdempotencyKey = UUID().uuidString.lowercased()
            evidenceTask?.cancel()
            evidenceTask = nil
            evidenceStartedAt = nil
            evidenceRetryAt = .distantPast
            refreshReadiness(now: clock())
        }
    }

    init(
        repository: CrownHuntMapRepository?,
        locationProvider: any LocationProvider,
        crownHuntEnabled: Bool,
        spawnEnabled: Bool,
        passesMemberGate: Bool,
        uid: String? = nil,
        nowProvider: @escaping () -> Date = Date.init
    ) {
        self.repository = repository
        self.locationProvider = locationProvider
        self.crownHuntEnabled = crownHuntEnabled
        self.spawnEnabled = spawnEnabled
        self.passesMemberGate = passesMemberGate
        self.uid = uid
        clock = nowProvider
    }

    deinit {
        fixTask?.cancel()
        refreshTask?.cancel()
        evidenceTask?.cancel()
        readinessTask?.cancel()
    }

    var isAvailable: Bool {
        repository != nil && crownHuntEnabled && passesMemberGate
    }

    func reportPlacement(
        target: CrownMapTarget,
        reason: CrownPlacementReportReason,
        comment: String
    ) async throws {
        guard isAvailable, let repository else { throw CrownMapRepositoryError.invalidResponse }
        try await repository.reportPlacement(target: target, reason: reason, comment: comment)
        reportedPlacements.insert(target.placementReportKey)
    }

    var markers: [MapCrownMarker] { markers(at: Date()) }

    func markers(at now: Date) -> [MapCrownMarker] {
        guard isAvailable else { return [] }
        let freshFix = tracker.latest(now: now)
        let pointMarkers = points.map { point in
            let inRange = pointIsInRange(point, fix: freshFix)
            let style = CrownMarkerStyle.point(inRange: inRange)
            return MapCrownMarker(
                id: "point:\(point.id)",
                longitude: point.longitude,
                latitude: point.latitude,
                discColorArgb: style.disc,
                iconName: "crown.fill",
                glyphColorArgb: style.glyph,
                glowColorArgb: nil,
                visualRank: CrownMarkerStyle.rank(forRewardPoints: point.rewardPoints),
                isInRange: inRange
            )
        }
        let spawnMarkers: [MapCrownMarker] = spawnEnabled ? spawns.filter { $0.expiresAt.map { $0 > now } ?? true }.map { spawn in
            let base = spawn.marker
            return MapCrownMarker(
                id: base.id,
                longitude: base.longitude,
                latitude: base.latitude,
                discColorArgb: base.discColorArgb,
                iconName: base.iconName,
                glyphColorArgb: base.glyphColorArgb,
                glowColorArgb: base.glowColorArgb,
                collectedByYou: collectedSpawnIds.contains(spawn.id),
                visualRank: base.visualRank,
                isInRange: freshFix.map { fix in
                    CrownGeo.distanceMeters(latitude: fix.latitude, longitude: fix.longitude,
                        toLatitude: spawn.latitude, toLongitude: spawn.longitude) <= spawn.collectRadiusMeters
                } ?? false
            )
        } : []
        return pointMarkers + spawnMarkers
    }

    func start() {
        guard fixTask == nil, isAvailable else { return }
        let stream = locationProvider.fixes()
        fixTask = Task { [weak self] in
            for await fix in stream {
                guard !Task.isCancelled, let self else { return }
                accept(fix)
            }
        }
        readinessTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                refreshReadiness(now: clock())
                do { try await Task.sleep(for: .milliseconds(evidenceRelevant ? 250 : 2000)) } catch { return }
            }
        }
    }

    /// Observation is driven by accepted physical samples, not receipt timestamps.
    private func accept(_ fix: LocationFix) {
        let before = tracker.fixes.last?.timestamp
        tracker.record(fix)
        if tracker.fixes.last?.timestamp != before { latestFix = tracker.fixes.last }
        refreshReadiness(now: clock())
    }

    private func refreshReadiness(now: Date) {
        let next = collectState(now: now)
        if readinessState != next { readinessState = next }
        reconcileEvidenceDemand(now: now)
    }

    private func reconcileEvidenceDemand(now: Date) {
        guard fixTask != nil else { return }
        let answered: Bool
        switch claimStatus { case .point, .spawn: answered = true; default: answered = false }
        let open = selectedTarget != nil && !answered
        let nearby = spawns.filter { spawn in
            guard !collectedSpawnIds.contains(spawn.id), let latest = tracker.latest(now: now) else { return false }
            return CrownGeo.distanceMeters(latitude: latest.latitude, longitude: latest.longitude,
                toLatitude: spawn.latitude, toLongitude: spawn.longitude) <= spawn.collectRadiusMeters
        }
        evidenceRelevant = open || !nearby.isEmpty
        let warm = nearby.contains { spawn in
            guard let proof = tracker.proof(for: spawn, now: now),
                  now.timeIntervalSince(proof.current.timestamp) < 8 else { return false }
            return CrownCollectGate.evaluate(spawn: spawn, latest: tracker.latest(now: now), proof: proof,
                                              enabled: spawnEnabled, now: now) == .ready
        }
        let wantsBurst = !open && !nearby.isEmpty && !warm && now >= evidenceRetryAt
        let burstExpired = !open && evidenceStartedAt.map { now.timeIntervalSince($0) >= 8 } == true
        if (!open && !wantsBurst) || burstExpired {
            if evidenceTask != nil {
                evidenceTask?.cancel()
                evidenceTask = nil
                evidenceStartedAt = nil
                if burstExpired { evidenceRetryAt = now.addingTimeInterval(3) }
            }
            return
        }
        guard evidenceTask == nil else { return }
        evidenceStartedAt = now
        let stream = locationProvider.crownCollectionFixes()
        evidenceTask = Task { [weak self] in
            for await fix in stream {
                guard !Task.isCancelled, let self else { return }
                accept(fix)
            }
        }
    }

    func stop() {
        fixTask?.cancel()
        fixTask = nil
        refreshTask?.cancel()
        refreshTask = nil
        evidenceTask?.cancel()
        evidenceTask = nil
        readinessTask?.cancel()
        readinessTask = nil
        evidenceStartedAt = nil
        evidenceRelevant = false
        evidenceRetryAt = .distantPast
        readinessState = .noPosition
        points = []
        spawns = []
        selectedTarget = nil
        latestFix = nil
        collectedSpawnIds = []
        tracker = CrownFixTracker()
        lastCellKeys = nil
        pointsLoaded = false
    }

    func refresh(camera: MapCameraSnapshot?, visibleRadiusMeters: Double?, force: Bool = false) async {
        guard isAvailable, let repository else { return }
        let keys: [String]
        if spawnEnabled, let camera {
            keys = CrownSpawnQuery.cellKeys(
                latitude: camera.latitude,
                longitude: camera.longitude,
                visibleRadiusMeters: visibleRadiusMeters
            )
        } else {
            keys = []
        }
        let cellsChanged = Set(keys) != Set(lastCellKeys ?? [])
        let shouldLoadSpawns = spawnEnabled && (force || cellsChanged)
        loading = true
        defer { loading = false }
        do {
            async let pointRead = loadPointsIfNeeded(repository: repository, force: force)
            if shouldLoadSpawns {
                async let spawnRead = repository.nearbySpawns(cellKeys: keys, now: Date())
                async let collectedRead = cellsChanged
                    ? loadCollectedIds(repository: repository, now: Date())
                    : []
                let (newPoints, newSpawns, collected) = try await (
                    pointRead, spawnRead, collectedRead
                )
                guard !Task.isCancelled else { return }
                points = newPoints
                pointsLoaded = true
                spawns = newSpawns
                // A refresh begun before an award must not resurrect the
                // reader's just-collected crown when its older read completes.
                collectedSpawnIds.formUnion(collected)
                lastCellKeys = keys
            } else {
                let newPoints = try await pointRead
                guard !Task.isCancelled else { return }
                points = newPoints
                pointsLoaded = true
                if !spawnEnabled { spawns = [] }
            }
            reconcileSelection()
        } catch is CancellationError {
            return
        } catch {
            // Keep the last good map snapshot on transient failures; clearing it
            // would misrepresent a network error as "there are no crowns".
        }
    }

    private func loadPointsIfNeeded(repository: CrownHuntMapRepository, force: Bool) async throws -> [CrownMapPoint] {
        if pointsLoaded && !force { return points }
        return try await repository.activePoints()
    }

    func select(markerId: String) {
        claimStatus = .idle
        if markerId.hasPrefix("spawn:"),
           let spawn = spawns.first(where: { $0.id == String(markerId.dropFirst(6)) }) {
            selectedTarget = .spawn(spawn)
        } else if markerId.hasPrefix("point:"),
                  let point = points.first(where: { $0.id == String(markerId.dropFirst(6)) }) {
            selectedTarget = .point(point)
        }
    }

    func dismissSelection() {
        selectedTarget = nil
        claimStatus = .idle
    }

    func selectedProximity(now: Date = Date()) -> CrownProximity? {
        guard let selectedTarget, let fix = tracker.latest(now: now) else { return nil }
        return CrownProximity(
            distanceMeters: CrownGeo.distanceMeters(
                latitude: fix.latitude, longitude: fix.longitude,
                toLatitude: selectedTarget.latitude, toLongitude: selectedTarget.longitude
            ),
            collectRadiusMeters: selectedTarget.collectRadiusMeters
        )
    }

    func collectState(now: Date = Date()) -> CrownCollectState {
        _ = latestFix
        _ = readinessState
        guard let selectedTarget else { return .noPosition }
        guard let liveFix = tracker.latest(now: now),
              now.timeIntervalSince(liveFix.timestamp) <= CrownSpawnLimits.maximumInteractionPositionAge else {
            return .noPosition
        }
        switch selectedTarget {
        case .spawn(let spawn):
            return CrownCollectGate.evaluate(
                spawn: spawn,
                latest: tracker.latest(now: now),
                proof: tracker.proof(for: spawn, now: now),
                enabled: spawnEnabled,
                now: now
            )
        case .point(let point):
            guard let fix = tracker.latest(now: now) else { return .noPosition }
            let distance = CrownGeo.distanceMeters(
                latitude: fix.latitude,
                longitude: fix.longitude,
                toLatitude: point.latitude,
                toLongitude: point.longitude
            )
            guard distance <= point.geofenceRadiusMeters else { return .tooFar(distance) }
            if let accuracy = fix.accuracyMeters, accuracy > point.geofenceRadiusMeters {
                return .waitingForSignal
            }
            if let speed = fix.speedMetersPerSecond,
               speed > CrownSpawnLimits.maximumSpeedMetersPerSecond {
                return .moving
            }
            return .ready
        }
    }

    func collect(now: Date = Date()) async {
        guard claimStatus != .collecting,
              collectState(now: now) == .ready,
              let target = selectedTarget,
              let repository else { return }
        claimStatus = .collecting
        do {
            switch target {
            case .point(let point):
                guard let fix = tracker.latest(now: now) else {
                    claimStatus = .idle
                    return
                }
                let outcome = try await repository.submitPointClaim(
                    pointId: point.id,
                    fix: fix,
                    idempotencyKey: claimIdempotencyKey
                )
                claimStatus = .point(outcome)
            case .spawn(let spawn):
                guard let proof = tracker.proof(for: spawn, now: now) else {
                    claimStatus = .idle
                    return
                }
                var outcome = try await repository.submitSpawnClaim(
                    spawnId: spawn.id, proof: proof, idempotencyKey: claimIdempotencyKey
                )
                if outcome.result == .positionTooOld {
                    // The first response is a definitive non-award. A new key is
                    // safe here; transport errors continue to reuse their key.
                    claimIdempotencyKey = UUID().uuidString.lowercased()
                    let retryNow = clock()
                    if selectedTarget?.id == target.id,
                       collectState(now: retryNow) == .ready,
                       let fresh = tracker.proof(for: spawn, now: retryNow),
                       fresh.current.timestamp > proof.current.timestamp {
                        outcome = try await repository.submitSpawnClaim(
                            spawnId: spawn.id, proof: fresh, idempotencyKey: claimIdempotencyKey
                        )
                    }
                    if outcome.result == .positionTooOld {
                        claimIdempotencyKey = UUID().uuidString.lowercased()
                        claimStatus = .idle
                        refreshReadiness(now: clock())
                        return
                    }
                }
                claimStatus = .spawn(outcome)
                if outcome.result == .awarded {
                    collectedSpawnIds.insert(spawn.id)
                    if spawn.rarity == .rare || spawn.rarity == .legendary {
                        spawns.removeAll { $0.id == spawn.id }
                        selectedTarget = nil
                    }
                }
            }
        } catch let error as KccFunctionsError {
            claimStatus = .failed(code: error.code.rawValue)
        } catch {
            claimStatus = .failed(code: nil)
        }
    }

    private func pointIsInRange(_ point: CrownMapPoint, fix: LocationFix?) -> Bool {
        guard let fix else { return false }
        return CrownGeo.distanceMeters(
            latitude: fix.latitude,
            longitude: fix.longitude,
            toLatitude: point.latitude,
            toLongitude: point.longitude
        ) <= point.geofenceRadiusMeters
    }

    private func reconcileSelection() {
        guard let selectedTarget else { return }
        switch selectedTarget {
        case .point(let selected):
            self.selectedTarget = points.first(where: { $0.id == selected.id }).map(CrownMapTarget.point)
        case .spawn(let selected):
            self.selectedTarget = spawns.first(where: { $0.id == selected.id }).map(CrownMapTarget.spawn)
        }
    }

    private func loadCollectedIds(
        repository: CrownHuntMapRepository,
        now: Date
    ) async throws -> Set<String> {
        guard let uid else { return [] }
        return try await repository.collectedSpawnIds(uid: uid, now: now)
    }
}
