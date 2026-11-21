import Foundation
import Observation

struct PartnerHeatCoordinate: Decodable, Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    var isValid: Bool { latitude.isFinite && longitude.isFinite && (-90...90).contains(latitude) && (-180...180).contains(longitude) }
}
struct PartnerHeatArea: Decodable, Equatable, Identifiable, Sendable {
    let cellId: String
    let latitude: Double
    let longitude: Double
    let boundary: [PartnerHeatCoordinate]
    let band: Int
    var id: String { cellId }
    var isValid: Bool {
        cellId.range(of: "^[0-9a-f]{15,16}$", options: .regularExpression) != nil &&
        PartnerHeatCoordinate(latitude: latitude, longitude: longitude).isValid &&
        (1...5).contains(band) && (3...10).contains(boundary.count) && boundary.allSatisfy(\.isValid)
    }
}
struct PartnerTrafficHeatSnapshot: Decodable, Equatable, Sendable {
    let generatedAt: String?
    let windowDays: Int
    let expiresInSeconds: Int
    let truncated: Bool
    let cells: [PartnerHeatArea]
    var isValid: Bool {
        windowDays == 90 && (0...172800).contains(expiresInSeconds) && cells.count <= 1000 &&
        Set(cells.map(\.cellId)).count == cells.count && cells.allSatisfy(\.isValid) &&
        (generatedAt == nil || PortalDate.parse(generatedAt!) != nil) &&
        (cells.isEmpty || (generatedAt != nil && expiresInSeconds > 0))
    }
}

/// No disk persistence. Monotonic request-start lifetime and account/generation fences.
@MainActor @Observable
final class PartnerTrafficHeatCoordinator {
    private(set) var snapshot: PartnerTrafficHeatSnapshot?
    private(set) var busy = false
    private(set) var errorKey: String?
    private(set) var expired = false
    private var generation = 0
    private var deadline: ContinuousClock.Instant?
    private var snapshotAccount: String?
    private var snapshotCompany: String?
    private let account: () -> String?
    private let loader: (String) async throws -> PartnerTrafficHeatSnapshot
    private let now: () -> ContinuousClock.Instant
    init(account: @escaping () -> String?, loader: @escaping (String) async throws -> PartnerTrafficHeatSnapshot,
         now: @escaping () -> ContinuousClock.Instant = { ContinuousClock().now }) {
        self.account = account; self.loader = loader; self.now = now
    }
    convenience init(client: PartnerPortalClient?) {
        self.init(account: { client?.trafficSessionKey }, loader: { companyId in
            guard let client else { throw KccFunctionsError(code: .unavailable) }
            return try await client.call("driveHeat", ["companyId": companyId], as: PartnerTrafficHeatSnapshot.self)
        })
    }
    func clear() {
        generation += 1
        snapshot = nil; errorKey = nil; busy = false; expired = false
        deadline = nil; snapshotAccount = nil; snapshotCompany = nil
    }
    func expireIfNeeded() {
        guard snapshot != nil else { return }
        // A delayed auth listener must never retain another account's map.
        guard account() == snapshotAccount else { clear(); return }
        if snapshot?.cells.isEmpty == false, let deadline, now() >= deadline {
            snapshot = nil; expired = true
            self.deadline = nil; snapshotAccount = nil; snapshotCompany = nil
        }
    }
    func load(companyId: String) async {
        guard let identity = account() else { clear(); return }
        expireIfNeeded()
        // Keep a still-valid map mounted while reauthorizing this same scope.
        // A different account/company, lifecycle clear, error, or expiry removes it.
        if snapshotAccount != identity || snapshotCompany != companyId { clear() }
        generation += 1
        let current = generation
        let started = now()
        errorKey = nil; expired = false; busy = true
        defer { if generation == current { busy = false } }
        do {
            let result = try await loader(companyId)
            guard generation == current else { return }
            guard !Task.isCancelled, account() == identity else { clear(); return }
            guard result.isValid else { throw KccFunctionsError(code: .internalError) }
            deadline = started.advanced(by: .seconds(result.expiresInSeconds))
            snapshotAccount = identity; snapshotCompany = companyId
            snapshot = result; expired = false
            expireIfNeeded()
        } catch is CancellationError {
            if generation == current { clear() }
        } catch {
            guard generation == current else { return }
            guard account() == identity else { clear(); return }
            clear()
            errorKey = PartnerPortalCoordinator.message(error)
        }
    }
}
