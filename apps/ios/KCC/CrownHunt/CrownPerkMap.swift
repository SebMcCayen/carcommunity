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
    let shieldUntil: Date?
    let boostUntil: Date?
    let traps: [CrownOwnTrap]

    static let empty = CrownPerkEffects(shieldUntil: nil, boostUntil: nil, traps: [])
}

struct CrownPerkDeployResult: Equatable, Sendable {
    let perkId: String
    let kind: PerkKind
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
    func effects(uid: String, now: Date) async -> CrownPerkEffects
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

    func effects(uid: String, now: Date) async -> CrownPerkEffects {
        do {
            async let shieldRead = firestore.collection("perkShieldPublic").document(uid).getDocument()
            async let boostRead = firestore.collection("perkBoost").document(uid).getDocument()
            let lowerBound = Timestamp(date: now.addingTimeInterval(-300))
            async let trapsRead = firestore.collection("activePerks")
                .whereField("placedByUid", isEqualTo: uid)
                .whereField("status", isEqualTo: "armed")
                .whereField("expiresAt", isGreaterThan: lowerBound)
                .limit(to: 10)
                .getDocuments()
            let (shield, boost, traps) = try await (shieldRead, boostRead, trapsRead)
            let ownTraps = traps.documents.compactMap { document -> CrownOwnTrap? in
                guard let latitude = (document.get("lat") as? NSNumber)?.doubleValue,
                      let longitude = (document.get("lng") as? NSNumber)?.doubleValue,
                      let expiresAt = (document.get("expiresAt") as? Timestamp)?.dateValue(),
                      expiresAt > now else { return nil }
                return CrownOwnTrap(
                    id: document.documentID,
                    latitude: latitude,
                    longitude: longitude,
                    expiresAt: expiresAt
                )
            }
            return CrownPerkEffects(
                shieldUntil: (shield.get("shieldedUntil") as? Timestamp)?.dateValue(),
                boostUntil: (boost.get("expiresAt") as? Timestamp)?.dateValue(),
                traps: ownTraps
            )
        } catch {
            return .empty
        }
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

    private(set) var inventory: [String: Int] = [:]
    private(set) var effects: CrownPerkEffects = .empty
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

    var isAvailable: Bool { enabled && repository != nil && shopRepository != nil && uid != nil }

    func start() {
        guard inventoryTask == nil, isAvailable, let uid, let shopRepository else { return }
        let stream = shopRepository.inventory(uid: uid)
        inventoryTask = Task { [weak self] in
            for await inventory in stream {
                guard !Task.isCancelled, let self else { return }
                self.inventory = inventory
            }
        }
        Task { await refreshEffects() }
    }

    func stop() {
        inventoryTask?.cancel()
        inventoryTask = nil
        inventory = [:]
        effects = .empty
        menuPresented = false
        status = .idle
    }

    func refreshEffects(now: Date = Date()) async {
        guard let repository, let uid, enabled else {
            effects = .empty
            return
        }
        effects = await repository.effects(uid: uid, now: now)
    }

    func deploy(perkId: String, kind: PerkKind) async {
        guard status.isIdle, let repository, enabled else { return }
        var latitude: Double?
        var longitude: Double?
        if kind == .trap {
            guard let fix = location(), abs(fix.latitude) <= 90, abs(fix.longitude) <= 180 else {
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
            status = .deployed(result)
            await refreshEffects()
        } catch let failure as CrownPerkDeployFailure {
            status = .failed(perkId, failure)
        } catch {
            status = .failed(perkId, .unknown)
        }
    }
}

private extension CrownPerkDeployStatus {
    var isIdle: Bool {
        if case .deploying = self { return false }
        return true
    }
}
