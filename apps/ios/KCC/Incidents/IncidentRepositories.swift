import Foundation

protocol IncidentRepository: AnyObject, Sendable {
    func report(type: IncidentType, at point: MapPoint, note: String?) async throws -> RoadIncident
    func listNearby(center: MapPoint, radiusMeters: Double) async throws -> [RoadIncident]
    func remove(incidentId: String) async throws
    func confirm(incidentId: String) async throws -> IncidentConfirmation
    func reportCleared(incidentId: String, fix: LocationFix) async throws -> IncidentClearResult
}

protocol PoliceRepository: AnyObject, Sendable {
    func report(at point: MapPoint, source: String) async throws -> PoliceReport
    func listNearby(center: MapPoint, radiusMeters: Double) async throws -> [PoliceReport]
    func remove(policeReportId: String) async throws -> Bool
    func confirm(policeReportId: String) async throws -> PoliceVerification
    func dispute(policeReportId: String) async throws -> PoliceVerification
}

final class FirebaseIncidentRepository: IncidentRepository, @unchecked Sendable {
    private let client: KccFunctionsClient
    private init(client: KccFunctionsClient) { self.client = client }

    static func createIfAvailable() -> FirebaseIncidentRepository? {
        KccFunctionsClient.createIfAvailable().map(FirebaseIncidentRepository.init(client:))
    }

    func report(type: IncidentType, at point: MapPoint, note: String?) async throws -> RoadIncident {
        var payload: [String: Any] = [
            "type": type.rawValue, "latitude": point.latitude, "longitude": point.longitude
        ]
        if let note = note?.trimmingCharacters(in: .whitespacesAndNewlines), !note.isEmpty {
            payload["note"] = note
        }
        guard let incident = IncidentWire.incident(
            try await client.call("incidents-report", payload: payload)
        ) else { throw KccFunctionsError(code: .internalError) }
        return incident
    }

    func listNearby(center: MapPoint, radiusMeters: Double) async throws -> [RoadIncident] {
        let raw = try await client.call("incidents-listNearby", payload: [
            "latitude": center.latitude, "longitude": center.longitude,
            "radiusMeters": IncidentViewport.radius(radiusMeters)
        ]) as? [String: Any]
        return (raw?["incidents"] as? [Any] ?? []).compactMap(IncidentWire.incident)
    }

    func remove(incidentId: String) async throws {
        _ = try await client.call("incidents-remove", payload: ["incidentId": incidentId])
    }

    func confirm(incidentId: String) async throws -> IncidentConfirmation {
        let raw = try await client.call(
            "incidents-confirm", payload: ["incidentId": incidentId]
        ) as? [String: Any]
        return IncidentConfirmation(
            confirmationCount: (raw?["confirmationCount"] as? NSNumber)?.intValue ?? 0,
            clearedCount: (raw?["clearedCount"] as? NSNumber)?.intValue ?? 0,
            reportedCleared: raw?["reportedCleared"] as? Bool ?? false,
            alreadyConfirmed: raw?["alreadyConfirmed"] as? Bool ?? false
        )
    }

    func reportCleared(incidentId: String, fix: LocationFix) async throws -> IncidentClearResult {
        let payload = Self.reportClearedPayload(incidentId: incidentId, fix: fix)
        do {
            let raw = try await client.call("incidents-reportCleared", payload: payload) as? [String: Any]
            return IncidentClearResult(
                clearedCount: (raw?["clearedCount"] as? NSNumber)?.intValue ?? 0,
                confirmationCount: (raw?["confirmationCount"] as? NSNumber)?.intValue ?? 0,
                reportedCleared: raw?["reportedCleared"] as? Bool ?? false,
                removed: raw?["removed"] as? Bool ?? false,
                alreadyVoted: raw?["alreadyVoted"] as? Bool ?? false
            )
        } catch let error as KccFunctionsError {
            if let reason = error.reason,
               let rejection = IncidentClearRejection(rawValue: reason.rawValue) {
                throw rejection
            }
            throw error
        }
    }

    static func reportClearedPayload(incidentId: String, fix: LocationFix) -> [String: Any] {
        var payload: [String: Any] = [
            "incidentId": incidentId, "latitude": fix.latitude, "longitude": fix.longitude,
            "capturedAt": IncidentWire.format(fix.timestamp)
        ]
        if let accuracy = fix.accuracyMeters { payload["accuracyMeters"] = accuracy }
        if let simulated = fix.isSimulatedBySoftware {
            payload["mockLocationReported"] = simulated
        }
        return payload
    }
}

final class FirebasePoliceRepository: PoliceRepository, @unchecked Sendable {
    private let client: KccFunctionsClient
    private init(client: KccFunctionsClient) { self.client = client }

    static func createIfAvailable() -> FirebasePoliceRepository? {
        KccFunctionsClient.createIfAvailable().map(FirebasePoliceRepository.init(client:))
    }

    func report(at point: MapPoint, source: String = "manual") async throws -> PoliceReport {
        guard let report = IncidentWire.police(try await client.call("police-report", payload: [
            "latitude": point.latitude, "longitude": point.longitude, "source": source
        ])) else { throw KccFunctionsError(code: .internalError) }
        return report
    }

    func listNearby(center: MapPoint, radiusMeters: Double) async throws -> [PoliceReport] {
        let raw = try await client.call("police-listNearby", payload: [
            "latitude": center.latitude, "longitude": center.longitude,
            "radiusMeters": IncidentViewport.radius(radiusMeters)
        ]) as? [String: Any]
        return (raw?["policeReports"] as? [Any] ?? []).compactMap(IncidentWire.police)
    }

    func remove(policeReportId: String) async throws -> Bool {
        let raw = try await client.call(
            "police-remove", payload: ["policeReportId": policeReportId]
        ) as? [String: Any]
        guard let removed = raw?["removed"] as? Bool else {
            throw KccFunctionsError(code: .internalError)
        }
        return removed
    }

    func confirm(policeReportId: String) async throws -> PoliceVerification {
        try await verify(name: "police-confirm", id: policeReportId)
    }

    func dispute(policeReportId: String) async throws -> PoliceVerification {
        try await verify(name: "police-dispute", id: policeReportId)
    }

    private func verify(name: String, id: String) async throws -> PoliceVerification {
        let raw = try await client.call(name, payload: ["policeReportId": id]) as? [String: Any]
        guard let resultId = raw?["policeReportId"] as? String else {
            throw KccFunctionsError(code: .internalError)
        }
        return PoliceVerification(
            policeReportId: resultId,
            confirmationCount: (raw?["confirmationCount"] as? NSNumber)?.intValue ?? 0,
            disputeCount: (raw?["disputeCount"] as? NSNumber)?.intValue ?? 0,
            alreadyVoted: raw?["alreadyVoted"] as? Bool ?? false,
            switched: raw?["switched"] as? Bool ?? false
        )
    }
}
