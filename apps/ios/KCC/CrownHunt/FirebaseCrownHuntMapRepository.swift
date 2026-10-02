import FirebaseCore
import FirebaseFirestore
import Foundation

final class FirebaseCrownHuntMapRepository: CrownHuntMapRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: KccFunctionsClient
    private let iso8601 = ISO8601DateFormatter()

    private init(firestore: Firestore, functions: KccFunctionsClient) {
        self.firestore = firestore
        self.functions = functions
        iso8601.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    }

    func activePoints() async throws -> [CrownMapPoint] {
        let snapshot = try await firestore.collection("crownHuntPoints")
            .whereField("status", isEqualTo: CrownHuntPointStatus.active.wire)
            .order(by: "createdAt", descending: true)
            .limit(to: 200)
            .getDocuments()
        return snapshot.documents.compactMap(Self.point(from:))
    }

    func nearbySpawns(cellKeys: [String], now: Date) async throws -> [CrownSpawn] {
        guard !cellKeys.isEmpty else { return [] }
        var merged: [String: CrownSpawn] = [:]
        for batch in CrownSpawnQuery.batches(cellKeys) {
            try Task.checkCancellation()
            let snapshot = try await firestore.collection("crownSpawns")
                .whereField("cellKey", in: batch)
                .whereField("status", isEqualTo: "live")
                .whereField("expiresAt", isGreaterThan: Timestamp(date: now))
                .limit(to: 250)
                .getDocuments()
            for document in snapshot.documents {
                if let spawn = Self.spawn(from: document) { merged[spawn.id] = spawn }
            }
        }
        return Array(merged.values.prefix(250))
    }

    func collectedSpawnIds(uid: String, now: Date) async throws -> Set<String> {
        let snapshot = try await firestore.collection("crownSpawnCollectors")
            .whereField("userId", isEqualTo: uid)
            .limit(to: 250)
            .getDocuments()
        return Set(snapshot.documents.compactMap { document in
            guard let spawnId = document.get("spawnId") as? String else { return nil }
            if let expiry = (document.get("expireAt") as? Timestamp)?.dateValue(), expiry <= now {
                return nil
            }
            return spawnId
        })
    }

    func submitPointClaim(
        pointId: String,
        fix: LocationFix,
        idempotencyKey: String
    ) async throws -> CrownPointClaimOutcome {
        var payload = coordinatePayload(fix)
        payload["pointId"] = pointId
        payload["idempotencyKey"] = idempotencyKey
        let raw = try await functions.call("crownHunt-submitClaim", payload: payload)
        guard let data = raw as? [String: Any],
              let wire = data["result"] as? String,
              let result = CrownHuntClaimResult.fromWire(wire) else {
            throw CrownMapRepositoryError.invalidResponse
        }
        return CrownPointClaimOutcome(
            result: result,
            pointsAwarded: (data["pointsAwarded"] as? NSNumber)?.intValue,
            newBalance: (data["newBalance"] as? NSNumber)?.intValue
        )
    }

    func submitSpawnClaim(
        spawnId: String,
        proof: CrownFixPair,
        idempotencyKey: String
    ) async throws -> CrownSpawnClaimOutcome {
        var payload = coordinatePayload(proof.current)
        payload["spawnId"] = spawnId
        payload["previousFix"] = coordinatePayload(proof.previous, includeMockSignal: false)
        payload["idempotencyKey"] = idempotencyKey
        let raw = try await functions.call("crownHunt-claimSpawn", payload: payload)
        guard let data = raw as? [String: Any],
              let wire = data["result"] as? String,
              let result = CrownSpawnClaimResult(rawValue: wire) else {
            throw CrownMapRepositoryError.invalidResponse
        }
        return CrownSpawnClaimOutcome(
            result: result,
            pointsAwarded: (data["pointsAwarded"] as? NSNumber)?.intValue,
            newBalance: (data["newBalance"] as? NSNumber)?.intValue,
            rarity: CrownRarity.fromWire(data["rarity"] as? String)
        )
    }

    private func coordinatePayload(
        _ fix: LocationFix,
        includeMockSignal: Bool = true
    ) -> [String: Any] {
        var payload: [String: Any] = [
            "latitude": fix.latitude,
            "longitude": fix.longitude,
            "recordedAt": iso8601.string(from: fix.timestamp)
        ]
        if let accuracy = fix.accuracyMeters { payload["accuracyMeters"] = accuracy }
        if let speed = fix.speedMetersPerSecond { payload["speedMetersPerSecond"] = speed }
        if includeMockSignal, let simulated = fix.isSimulatedBySoftware {
            payload["isMockLocation"] = simulated
        }
        return payload
    }

    private static func point(from document: DocumentSnapshot) -> CrownMapPoint? {
        guard document.exists,
              let title = document.get("title") as? String,
              let reward = (document.get("rewardPoints") as? NSNumber)?.intValue,
              let latitude = (document.get("latitude") as? NSNumber)?.doubleValue,
              let longitude = (document.get("longitude") as? NSNumber)?.doubleValue,
              latitude.isFinite, longitude.isFinite else { return nil }
        return CrownMapPoint(
            id: document.documentID,
            title: title,
            detail: document.get("description") as? String,
            rewardPoints: reward,
            latitude: latitude,
            longitude: longitude,
            geofenceRadiusMeters: CrownSpawnLimits.collectRadius(
                (document.get("geofenceRadiusMeters") as? NSNumber)?.doubleValue
            )
        )
    }

    private static func spawn(from document: DocumentSnapshot) -> CrownSpawn? {
        guard document.exists,
              let latitude = (document.get("latitude") as? NSNumber)?.doubleValue,
              let longitude = (document.get("longitude") as? NSNumber)?.doubleValue,
              let rarity = CrownRarity.fromWire(document.get("rarity") as? String),
              latitude.isFinite, longitude.isFinite else { return nil }
        let rewards: [CrownRarity: Int] = [.common: 10, .uncommon: 25, .rare: 100, .legendary: 500]
        return CrownSpawn(
            id: document.documentID,
            latitude: latitude,
            longitude: longitude,
            rarity: rarity,
            rewardPoints: (document.get("rewardPoints") as? NSNumber)?.intValue ?? rewards[rarity]!,
            collectRadiusMeters: CrownSpawnLimits.collectRadius(
                (document.get("collectRadiusMeters") as? NSNumber)?.doubleValue
            ),
            expiresAt: (document.get("expiresAt") as? Timestamp)?.dateValue()
        )
    }

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseCrownHuntMapRepository?

    static func createIfAvailable() -> CrownHuntMapRepository? {
        guard FirebaseApp.app() != nil, let functions = KccFunctionsClient.createIfAvailable() else {
            return nil
        }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let repository = FirebaseCrownHuntMapRepository(
            firestore: CrownHuntFirebase.firestore(),
            functions: functions
        )
        cached = repository
        return repository
    }
}

enum CrownMapRepositoryError: Error {
    case invalidResponse
}
