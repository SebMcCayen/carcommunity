import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
import Foundation
import Observation

struct CrownOwnTrap: Equatable, Sendable, Identifiable {
    let id: String
    let latitude: Double
    let longitude: Double
    let expiresAt: Date
}

struct CrownPerkEffects: Equatable, Sendable {
    var shieldUntil: Date?
    var boostUntil: Date?
    var traps: [CrownOwnTrap]

    static let empty = CrownPerkEffects(shieldUntil: nil, boostUntil: nil, traps: [])

    func activity(for kind: PerkKind, at now: Date) -> CrownPerkActivity {
        switch kind {
        case .trap:
            let count = traps.count { $0.expiresAt > now }
            return count > 0 ? .traps(count) : .inactive
        case .shield:
            return Self.timedActivity(until: shieldUntil, at: now)
        case .boost:
            return Self.timedActivity(until: boostUntil, at: now)
        }
    }

    private static func timedActivity(until expiry: Date?, at now: Date) -> CrownPerkActivity {
        guard let expiry, expiry > now else { return .inactive }
        return .timed(seconds: Int(expiry.timeIntervalSince(now).rounded(.up)))
    }
}

enum CrownPerkActivity: Equatable {
    case inactive
    case traps(Int)
    case timed(seconds: Int)
}

enum CrownPerkEffectSnapshot: Sendable {
    case shield(Date?)
    case boost(Date?)
    case traps([CrownOwnTrap])
    case failed
}

struct CrownPerkDeployResult: Equatable, Sendable {
    let perkId: String
    let kind: PerkKind
    let effectId: String
    let expiresAt: Date
    let inventoryCount: Int
    let alreadyDeployed: Bool
}

enum CrownPerkDeployFailure: Error, Equatable, Sendable {
    case noLocation
    case activationLimit
    case eventTooClose
    case unavailable
    case unknown
}

protocol CrownPerkDeployRepository: AnyObject, Sendable {
    func effects(uid: String, now: Date) -> AsyncStream<CrownPerkEffectSnapshot>
    func deploy(
        perkId: String,
        latitude: Double?,
        longitude: Double?,
        idempotencyKey: String
    ) async throws -> CrownPerkDeployResult
}

final class FirebaseCrownPerkDeployRepository: CrownPerkDeployRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: Functions

    private init(firestore: Firestore, functions: Functions) {
        self.firestore = firestore
        self.functions = functions
    }

    func effects(uid: String, now: Date) -> AsyncStream<CrownPerkEffectSnapshot> {
        let shield = firestore.collection("perkShieldPublic").document(uid)
        let boost = firestore.collection("perkBoost").document(uid)
        let traps = firestore.collection("activePerks")
            .whereField("placedByUid", isEqualTo: uid)
            .whereField("status", isEqualTo: "armed")
            .whereField("expiresAt", isGreaterThan: Timestamp(date: now.addingTimeInterval(-300)))
        return AsyncStream { continuation in
            let listeners = [
                shield.addSnapshotListener { snapshot, error in
                    if error != nil {
                        continuation.yield(.failed)
                    } else {
                        continuation.yield(.shield((snapshot?.get("shieldedUntil") as? Timestamp)?.dateValue()))
                    }
                },
                boost.addSnapshotListener { snapshot, error in
                    if error != nil {
                        continuation.yield(.failed)
                    } else {
                        continuation.yield(.boost((snapshot?.get("expiresAt") as? Timestamp)?.dateValue()))
                    }
                },
                traps.addSnapshotListener { snapshot, error in
                    if error != nil {
                        continuation.yield(.failed)
                    } else {
                        let traps = snapshot?.documents.compactMap(Self.ownTrap) ?? []
                        continuation.yield(.traps(traps))
                    }
                }
            ].map { CrownHuntListenerBox(registration: $0) }
            continuation.onTermination = { _ in
                listeners.forEach { $0.registration.remove() }
            }
        }
    }

    private static func ownTrap(_ document: QueryDocumentSnapshot) -> CrownOwnTrap? {
        guard let latitude = (document.get("lat") as? NSNumber)?.doubleValue,
              let longitude = (document.get("lng") as? NSNumber)?.doubleValue,
              let expiresAt = (document.get("expiresAt") as? Timestamp)?.dateValue() else { return nil }
        return CrownOwnTrap(id: document.documentID, latitude: latitude, longitude: longitude, expiresAt: expiresAt)
    }

    func deploy(
        perkId: String,
        latitude: Double?,
        longitude: Double?,
        idempotencyKey: String
    ) async throws -> CrownPerkDeployResult {
        var payload: [String: Any] = ["perkId": perkId, "idempotencyKey": idempotencyKey]
        if let latitude, let longitude {
            payload["latitude"] = latitude
            payload["longitude"] = longitude
        }
        do {
            let response = try await functions.httpsCallable("crownHunt-deployPerk").call(payload)
            guard let data = response.data as? [String: Any],
                  let perkId = data["perkId"] as? String,
                  let kind = PerkKind.fromWire(data["kind"] as? String),
                  let expiresAtText = data["expiresAt"] as? String,
                  let expiresAt = Self.parseISO8601(expiresAtText),
                  let inventoryCount = (data["inventoryCount"] as? NSNumber)?.intValue else {
                throw CrownPerkDeployFailure.unknown
            }
            return CrownPerkDeployResult(
                perkId: perkId,
                kind: kind,
                effectId: data["effectId"] as? String ?? idempotencyKey,
                expiresAt: expiresAt,
                inventoryCount: inventoryCount,
                alreadyDeployed: data["alreadyDeployed"] as? Bool ?? false
            )
        } catch let mapped as CrownPerkDeployFailure {
            throw mapped
        } catch {
            let nsError = error as NSError
            guard nsError.domain == FunctionsErrorDomain,
                  let code = FunctionsErrorCode(rawValue: nsError.code) else {
                throw CrownPerkDeployFailure.unknown
            }
            let reason = (nsError.userInfo[FunctionsErrorDetailsKey] as? [String: Any])?["reason"] as? String
            if code == .invalidArgument { throw CrownPerkDeployFailure.noLocation }
            guard code == .failedPrecondition else { throw CrownPerkDeployFailure.unknown }
            switch reason {
            case "activation_limit": throw CrownPerkDeployFailure.activationLimit
            case "event_too_close": throw CrownPerkDeployFailure.eventTooClose
            default: throw CrownPerkDeployFailure.unavailable
            }
        }
    }

    private static func parseISO8601(_ value: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseCrownPerkDeployRepository?

    static func createIfAvailable() -> CrownPerkDeployRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let repository = FirebaseCrownPerkDeployRepository(
            firestore: CrownHuntFirebase.firestore(),
            functions: CrownHuntFirebase.functions()
        )
        cached = repository
        return repository
    }
}

enum CrownPerkDeployStatus: Equatable, Sendable {
    case idle
    case deploying(String)
    case deployed(CrownPerkDeployResult)
    case failed(String, CrownPerkDeployFailure)
}

@MainActor
@Observable
final class CrownPerkMapCoordinator {
    private let repository: CrownPerkDeployRepository?
    private let shopRepository: PerkShopRepository?
    private let uid: String?
    private let enabled: Bool
    private let location: () -> LocationFix?
    @ObservationIgnored private var inventoryTask: Task<Void, Never>?
    @ObservationIgnored private var effectsTask: Task<Void, Never>?
    @ObservationIgnored private var lifecycleGeneration = 0
    private var effectRevisions: [PerkKind: Int] = [:]

    private(set) var inventory: [String: Int] = [:]
    private(set) var hasLoadedInventory = false
    private(set) var effects: CrownPerkEffects = .empty
    var hasLoadedEffects: Bool { effectRevisions.count == PerkKind.allCases.count }
    private(set) var effectsLoadFailed = false
    private(set) var status: CrownPerkDeployStatus = .idle
    var menuPresented = false

    init(
        repository: CrownPerkDeployRepository?,
        shopRepository: PerkShopRepository?,
        uid: String?,
        enabled: Bool,
        location: @escaping () -> LocationFix?
    ) {
        self.repository = repository
        self.shopRepository = shopRepository
        self.uid = uid
        self.enabled = enabled
        self.location = location
    }

    deinit {
        inventoryTask?.cancel()
        effectsTask?.cancel()
    }

    var isAvailable: Bool { enabled && repository != nil && shopRepository != nil && uid != nil }

    func start() {
        guard inventoryTask == nil, isAvailable, let uid, let shopRepository else { return }
        lifecycleGeneration += 1
        let stream = shopRepository.inventory(uid: uid)
        inventoryTask = Task { [weak self] in
            for await inventory in stream {
                guard !Task.isCancelled, let self else { return }
                self.inventory = inventory
                self.hasLoadedInventory = true
            }
        }
        startEffects()
    }

    private func startEffects() {
        guard isAvailable, let uid, let repository else { return }
        effectsTask?.cancel()
        effectRevisions = [:]
        effectsLoadFailed = false
        let stream = repository.effects(uid: uid, now: Date())
        effectsTask = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.receiveEffects(snapshot)
            }
        }
    }

    private func receiveEffects(_ snapshot: CrownPerkEffectSnapshot) {
        switch snapshot {
        case .shield(let expiry):
            effects.shieldUntil = expiry
            effectRevisions[.shield, default: 0] += 1
        case .boost(let expiry):
            effects.boostUntil = expiry
            effectRevisions[.boost, default: 0] += 1
        case .traps(let traps):
            effects.traps = traps
            effectRevisions[.trap, default: 0] += 1
        case .failed:
            effectsLoadFailed = true
        }
    }

    func stop() {
        lifecycleGeneration += 1
        inventoryTask?.cancel()
        inventoryTask = nil
        effectsTask?.cancel()
        effectsTask = nil
        inventory = [:]
        hasLoadedInventory = false
        effects = .empty
        effectRevisions = [:]
        effectsLoadFailed = false
        menuPresented = false
        status = .idle
    }

    func catalog() -> AsyncStream<PerkCatalogSnapshot>? {
        guard isAvailable else { return nil }
        return shopRepository?.catalog()
    }

    func resetStatus() {
        if !status.isDeploying { status = .idle }
    }

    func openMenu() {
        if effectsLoadFailed { startEffects() }
        menuPresented = true
    }

    func canDeploy(perkId: String, kind: PerkKind, now: Date) -> Bool {
        // The backend allows one armed trap and can consume a second timed
        // perk to replace an active one. Match Android's client-side guard.
        isAvailable && hasLoadedEffects && !effectsLoadFailed && !status.isDeploying
            && (inventory[perkId] ?? 0) > 0
            && effects.activity(for: kind, at: now) == .inactive
    }

    func deploy(perkId: String, kind: PerkKind, now: Date = Date()) async {
        guard canDeploy(perkId: perkId, kind: kind, now: now), let repository else { return }
        let generation = lifecycleGeneration
        let effectRevision = effectRevisions[kind]
        var latitude: Double?
        var longitude: Double?
        if kind == .trap {
            guard let fix = location(),
                  (0...CrownSpawnLimits.maximumPositionAge).contains(now.timeIntervalSince(fix.timestamp)),
                  abs(fix.latitude) <= 90,
                  abs(fix.longitude) <= 180 else {
                status = .failed(perkId, .noLocation)
                return
            }
            latitude = fix.latitude
            longitude = fix.longitude
        }
        status = .deploying(perkId)
        do {
            let result = try await repository.deploy(
                perkId: perkId,
                latitude: latitude,
                longitude: longitude,
                idempotencyKey: UUID().uuidString.lowercased()
            )
            guard !Task.isCancelled, generation == lifecycleGeneration else { return }
            // Reflect a confirmed activation before Firestore echoes it, but
            // keep a newer listener update (such as a trap already triggered).
            if result.kind == kind, !result.alreadyDeployed, effectRevisions[kind] == effectRevision {
                switch kind {
                case .shield: effects.shieldUntil = result.expiresAt
                case .boost: effects.boostUntil = result.expiresAt
                case .trap:
                    if let latitude, let longitude {
                        effects.traps.append(CrownOwnTrap(
                            id: result.effectId, latitude: latitude, longitude: longitude, expiresAt: result.expiresAt
                        ))
                    }
                }
            }
            status = menuPresented ? .deployed(result) : .idle
        } catch let failure as CrownPerkDeployFailure {
            guard !Task.isCancelled, generation == lifecycleGeneration else { return }
            status = menuPresented ? .failed(perkId, failure) : .idle
        } catch {
            guard !Task.isCancelled, generation == lifecycleGeneration else { return }
            status = menuPresented ? .failed(perkId, .unknown) : .idle
        }
    }
}

extension CrownPerkDeployStatus {
    var isDeploying: Bool {
        if case .deploying = self { return true }
        return false
    }
}
