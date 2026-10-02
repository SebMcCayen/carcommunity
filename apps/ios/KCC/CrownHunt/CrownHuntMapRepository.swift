import Foundation

protocol CrownHuntMapRepository: AnyObject, Sendable {
    func activePoints() async throws -> [CrownMapPoint]
    func nearbySpawns(cellKeys: [String], now: Date) async throws -> [CrownSpawn]
    func collectedSpawnIds(uid: String, now: Date) async throws -> Set<String>
    func submitPointClaim(
        pointId: String,
        fix: LocationFix,
        idempotencyKey: String
    ) async throws -> CrownPointClaimOutcome
    func submitSpawnClaim(
        spawnId: String,
        proof: CrownFixPair,
        idempotencyKey: String
    ) async throws -> CrownSpawnClaimOutcome
}
