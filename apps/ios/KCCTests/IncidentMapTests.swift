import XCTest

@testable import KCC

final class IncidentMapTests: XCTestCase {
    func testWireDropsMalformedRowsAndDefaultsOptionalFields() {
        XCTAssertNil(IncidentWire.incident(["id": "bad", "type": "unknown"]))
        let parsed = IncidentWire.incident([
            "id": "i1", "type": "hazard", "latitude": 57.48, "longitude": 12.07
        ])
        XCTAssertEqual(parsed?.id, "i1")
        XCTAssertEqual(parsed?.type, .hazard)
        XCTAssertEqual(parsed?.confirmationCount, 0)
        XCTAssertFalse(parsed?.reportedCleared ?? true)
    }

    func testViewportRadiusIsDefensivelyClamped() {
        XCTAssertEqual(IncidentViewport.radius(nil), 15_000)
        XCTAssertEqual(IncidentViewport.radius(.nan), 15_000)
        XCTAssertEqual(IncidentViewport.radius(1), 100)
        XCTAssertEqual(IncidentViewport.radius(75_000), 50_000)
    }

    func testPoliceMarkersSuppressCoincidentIncident() {
        let incident = Self.incident(id: "incident", type: .police)
        let same = Self.police(id: "same", latitude: incident.latitude, longitude: incident.longitude)
        let other = Self.police(id: "other", latitude: incident.latitude + 0.01, longitude: incident.longitude)
        let markers = PoliceMapMarker.markers(pins: [same, other], incidents: [incident])
        XCTAssertEqual(markers.map(\.id), ["police:other"])
    }

    func testProximitySkipsOwnExpiredAndPreviouslyAlertedPins() {
        let now = Date(timeIntervalSince1970: 1_000)
        let fix = LocationFix.of(latitude: 57.48, longitude: 12.07, timestamp: now)!
        let fresh = Self.police(id: "fresh", expiresAt: now.addingTimeInterval(60))
        let mine = Self.police(id: "mine", expiresAt: now.addingTimeInterval(60), mine: true)
        let expired = Self.police(id: "expired", expiresAt: now.addingTimeInterval(-1))
        XCTAssertEqual(
            PoliceProximity.newAlerts(
                driver: fix, pins: [fresh, mine, expired], alreadyAlerted: [], now: now
            ).map(\.id),
            ["fresh"]
        )
        XCTAssertTrue(PoliceProximity.newAlerts(
            driver: fix, pins: [fresh], alreadyAlerted: ["fresh"], now: now
        ).isEmpty)
    }

    @MainActor
    func testPoliceIncidentReportCreatesBothRecordsAndOneVisibleMarker() async {
        let incidentRepository = FakeIncidentRepository()
        let policeRepository = FakePoliceRepository()
        let coordinator = IncidentMapCoordinator(
            incidentRepository: incidentRepository,
            policeRepository: policeRepository,
            currentUid: "me"
        )
        let surface = StubMapSurface(initialState: .loaded, autoLoad: false)
        coordinator.start(surface: surface, provider: StubLocationProvider())
        defer { coordinator.stop() }

        await coordinator.report(.police, at: MapPoint(longitude: 12.07, latitude: 57.48))

        XCTAssertEqual(incidentRepository.reportCalls, 1)
        XCTAssertEqual(policeRepository.reportSources, ["manual"])
        XCTAssertEqual(coordinator.incidents.count, 1)
        XCTAssertEqual(coordinator.policeReports.count, 1)
        XCTAssertEqual(surface.incidentMarkers.count, 1, "coincident police layers must de-duplicate")
        XCTAssertEqual(coordinator.feedback, .success("incidents.reportSuccess"))
    }

    @MainActor
    func testConfirmPatchesCountsAndClearWithoutFixDoesNotCallBackend() async {
        let repository = FakeIncidentRepository()
        let coordinator = IncidentMapCoordinator(
            incidentRepository: repository,
            policeRepository: nil,
            currentUid: "viewer"
        )
        let surface = StubMapSurface(initialState: .loaded, autoLoad: false)
        await coordinator.report(.hazard, at: MapPoint(longitude: 12.07, latitude: 57.48))
        coordinator.selectMarker(id: "incident")

        await coordinator.confirmSelectedIncident()
        XCTAssertEqual(coordinator.selectedIncident?.confirmationCount, 3)

        await coordinator.clearSelectedIncident()
        XCTAssertEqual(repository.clearCalls, 0)
        XCTAssertEqual(coordinator.feedback, .error("incidents.clearedNoLocation"))
        _ = surface
    }

    private static func incident(
        id: String,
        type: IncidentType,
        latitude: Double = 57.48,
        longitude: Double = 12.07
    ) -> RoadIncident {
        RoadIncident(
            id: id, type: type, longitude: longitude, latitude: latitude,
            note: nil, source: "user", reporterUid: "reporter",
            createdAt: nil, postedAt: nil, confirmationCount: 0,
            clearedCount: 0, reportedCleared: false
        )
    }

    private static func police(
        id: String,
        latitude: Double = 57.48,
        longitude: Double = 12.07,
        expiresAt: Date = Date().addingTimeInterval(60),
        mine: Bool = false
    ) -> PoliceReport {
        PoliceReport(
            id: id, latitude: latitude, longitude: longitude, source: "manual",
            expiresAt: expiresAt, mine: mine, confirmationCount: 0, disputeCount: 0
        )
    }

    private final class FakeIncidentRepository: IncidentRepository, @unchecked Sendable {
        private let lock = NSLock()
        private(set) var reportCalls = 0
        private(set) var clearCalls = 0

        func report(type: IncidentType, at point: MapPoint, note: String?) async throws -> RoadIncident {
            lock.withLock { reportCalls += 1 }
            return IncidentMapTests.incident(
                id: "incident", type: type, latitude: point.latitude, longitude: point.longitude
            )
        }

        func listNearby(center: MapPoint, radiusMeters: Double) async throws -> [RoadIncident] { [] }
        func remove(incidentId: String) async throws {}
        func confirm(incidentId: String) async throws -> IncidentConfirmation {
            IncidentConfirmation(
                confirmationCount: 3, clearedCount: 1,
                reportedCleared: false, alreadyConfirmed: false
            )
        }
        func reportCleared(incidentId: String, fix: LocationFix) async throws -> IncidentClearResult {
            lock.withLock { clearCalls += 1 }
            return IncidentClearResult(
                clearedCount: 1, confirmationCount: 3, reportedCleared: false,
                removed: false, alreadyVoted: false
            )
        }
    }

    private final class FakePoliceRepository: PoliceRepository, @unchecked Sendable {
        private let lock = NSLock()
        private(set) var reportSources: [String] = []

        func report(at point: MapPoint, source: String) async throws -> PoliceReport {
            lock.withLock { reportSources.append(source) }
            return IncidentMapTests.police(
                id: "police", latitude: point.latitude, longitude: point.longitude
            )
        }
        func listNearby(center: MapPoint, radiusMeters: Double) async throws -> [PoliceReport] { [] }
        func remove(policeReportId: String) async throws -> Bool { true }
        func confirm(policeReportId: String) async throws -> PoliceVerification {
            PoliceVerification(
                policeReportId: policeReportId, confirmationCount: 1,
                disputeCount: 0, alreadyVoted: false, switched: false
            )
        }
        func dispute(policeReportId: String) async throws -> PoliceVerification {
            PoliceVerification(
                policeReportId: policeReportId, confirmationCount: 0,
                disputeCount: 1, alreadyVoted: false, switched: false
            )
        }
    }
}
