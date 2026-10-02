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

    @ObservationIgnored private var fixTask: Task<Void, Never>?
    @ObservationIgnored private var refreshTask: Task<Void, Never>?
    @ObservationIgnored private var tracker = CrownFixTracker()
    @ObservationIgnored private var lastCellKeys: [String]?
    @ObservationIgnored private var pointsLoaded = false

    private(set) var points: [CrownMapPoint] = []
    private(set) var spawns: [CrownSpawn] = []
    private(set) var latestFix: LocationFix?
    private(set) var loading = false
    private(set) var claimStatus: CrownMapClaimStatus = .idle
    private(set) var collectedSpawnIds: Set<String> = []
    var selectedTarget: CrownMapTarget?

    init(
        repository: CrownHuntMapRepository?,
        locationProvider: any LocationProvider,
        crownHuntEnabled: Bool,
        spawnEnabled: Bool,
        passesMemberGate: Bool,
        uid: String? = nil
    ) {
        self.repository = repository
        self.locationProvider = locationProvider
        self.crownHuntEnabled = crownHuntEnabled
        self.spawnEnabled = spawnEnabled
        self.passesMemberGate = passesMemberGate
        self.uid = uid
    }

    deinit {
        fixTask?.cancel()
        refreshTask?.cancel()
    }

    var isAvailable: Bool {
        repository != nil && crownHuntEnabled && passesMemberGate
    }

    var markers: [MapCrownMarker] {
        guard isAvailable else { return [] }
        let pointMarkers = points.map { point in
            let style = CrownMarkerStyle.point(inRange: pointIsInRange(point))
            return MapCrownMarker(
                id: "point:\(point.id)",
                longitude: point.longitude,
                latitude: point.latitude,
                discColorArgb: style.disc,
                iconName: "crown.fill",
                glyphColorArgb: style.glyph,
                glowColorArgb: nil
            )
        }
        let spawnMarkers: [MapCrownMarker] = spawnEnabled ? spawns.map { spawn in
            let base = spawn.marker
            return MapCrownMarker(
                id: base.id,
                longitude: base.longitude,
                latitude: base.latitude,
                discColorArgb: base.discColorArgb,
                iconName: base.iconName,
                glyphColorArgb: base.glyphColorArgb,
                glowColorArgb: base.glowColorArgb,
                collectedByYou: collectedSpawnIds.contains(spawn.id)
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
                tracker.record(fix)
                latestFix = fix
            }
        }
    }

    func stop() {
        fixTask?.cancel()
        fixTask = nil
        refreshTask?.cancel()
        refreshTask = nil
        points = []
        spawns = []
        selectedTarget = nil
        latestFix = nil
        collectedSpawnIds = []
        tracker = CrownFixTracker()
        lastCellKeys = nil
        pointsLoaded = false
    }

    func refresh(camera: MapCameraSnapshot?, visibleRadiusMeters: Double?) async {
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
        let shouldLoadSpawns = spawnEnabled && Set(keys) != Set(lastCellKeys ?? [])
        loading = true
        defer { loading = false }
        do {
            async let pointRead = loadPointsIfNeeded(repository: repository)
            if shouldLoadSpawns {
                async let spawnRead = repository.nearbySpawns(cellKeys: keys, now: Date())
                async let collectedRead = loadCollectedIds(repository: repository, now: Date())
                let (newPoints, newSpawns, collected) = try await (
                    pointRead, spawnRead, collectedRead
                )
                guard !Task.isCancelled else { return }
                points = newPoints
                pointsLoaded = true
                spawns = newSpawns
                collectedSpawnIds = collected
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

    private func loadPointsIfNeeded(repository: CrownHuntMapRepository) async throws -> [CrownMapPoint] {
        if pointsLoaded { return points }
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

    func collectState(now: Date = Date()) -> CrownCollectState {
        guard let selectedTarget else { return .noPosition }
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
                    idempotencyKey: UUID().uuidString.lowercased()
                )
                claimStatus = .point(outcome)
            case .spawn(let spawn):
                guard let proof = tracker.proof(for: spawn, now: now) else {
                    claimStatus = .idle
                    return
                }
                let outcome = try await repository.submitSpawnClaim(
                    spawnId: spawn.id,
                    proof: proof,
                    idempotencyKey: UUID().uuidString.lowercased()
                )
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

    private func pointIsInRange(_ point: CrownMapPoint) -> Bool {
        guard let fix = latestFix else { return false }
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
