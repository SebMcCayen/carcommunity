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

    @MainActor
    func testCurrentLocationReportWaitsForFirstAuthorizedFixWithoutPrompting() async {
        let repository = FakeIncidentRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = IncidentMapCoordinator(
            incidentRepository: repository, policeRepository: nil, currentUid: "me",
            fixWaitTimeout: .seconds(1)
        )
        coordinator.start(
            surface: StubMapSurface(initialState: .loaded, autoLoad: false),
            provider: provider
        )
        defer { coordinator.stop() }

        let reporting = Task { await coordinator.report(.hazard, at: nil) }
        try? await Task.sleep(for: .milliseconds(100))
        provider.emitFix(Self.fix())
        await reporting.value

        XCTAssertEqual(repository.reportCalls, 1)
        XCTAssertEqual(provider.whenInUseRequestCount, 0)
    }

    @MainActor
    func testRevocationClearsCachedFixAndBlocksCurrentLocationUploads() async {
        let incidentRepository = FakeIncidentRepository()
        let policeRepository = FakePoliceRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = IncidentMapCoordinator(
            incidentRepository: incidentRepository,
            policeRepository: policeRepository,
            currentUid: "me",
            fixWaitTimeout: .milliseconds(50)
        )
        coordinator.start(
            surface: StubMapSurface(initialState: .loaded, autoLoad: false),
            provider: provider
        )
        defer { coordinator.stop() }
        provider.emitFix(Self.fix())
        await waitUntil { coordinator.latestFix != nil }
        provider.setAuthorization(.denied)

        await coordinator.report(.hazard, at: nil)
        let convoyReported = await coordinator.reportPoliceAtCurrentLocation()

        XCTAssertNil(coordinator.latestFix)
        XCTAssertEqual(incidentRepository.reportCalls, 0)
        XCTAssertFalse(convoyReported)
        XCTAssertTrue(policeRepository.reportSources.isEmpty)
        XCTAssertEqual(provider.whenInUseRequestCount, 0)
    }

    @MainActor
    func testClearVoteRejectsStaleFix() async {
        let now = Date(timeIntervalSince1970: 10_000)
        let repository = FakeIncidentRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = IncidentMapCoordinator(
            incidentRepository: repository, policeRepository: nil, currentUid: "viewer",
            now: { now }, maximumFixAge: 30, fixWaitTimeout: .milliseconds(50)
        )
        coordinator.start(
            surface: StubMapSurface(initialState: .loaded, autoLoad: false),
            provider: provider
        )
        defer { coordinator.stop() }
        await coordinator.report(.hazard, at: MapPoint(longitude: 12.07, latitude: 57.48))
        coordinator.selectMarker(id: "incident")
        provider.emitFix(Self.fix(timestamp: now.addingTimeInterval(-31)))
        await waitUntil { coordinator.latestFix != nil }

        await coordinator.clearSelectedIncident()

        XCTAssertEqual(repository.clearCalls, 0)
        XCTAssertEqual(coordinator.feedback, .error("incidents.clearedNoLocation"))
    }

    @MainActor
    func testPoliceAlertsAreQueuedAndMarkedOnlyWhenDisplayed() async {
        let now = Date(timeIntervalSince1970: 10_000)
        let police = FakePoliceRepository()
        police.nearby = [
            Self.police(id: "first", expiresAt: now.addingTimeInterval(60)),
            Self.police(id: "second", latitude: 57.4801, expiresAt: now.addingTimeInterval(60))
        ]
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = IncidentMapCoordinator(
            incidentRepository: FakeIncidentRepository(), policeRepository: police,
            currentUid: "me", now: { now }, pollInterval: .seconds(60)
        )
        let surface = Self.surface()
        coordinator.start(surface: surface, provider: provider)
        defer { coordinator.stop() }
        provider.emitFix(Self.fix(timestamp: now))
        await Task.yield()
        await coordinator.refresh(surface: surface)

        XCTAssertEqual(coordinator.proximityAlert?.id, "first")
        coordinator.dismissProximityAlert()
        XCTAssertEqual(coordinator.proximityAlert?.id, "second")
        coordinator.dismissProximityAlert()
        XCTAssertNil(coordinator.proximityAlert)
    }

    @MainActor
    func testOlderRefreshCannotOverwriteNewerReportMutation() async {
        let repository = FakeIncidentRepository()
        repository.nearby = [Self.incident(id: "stale", type: .roadwork)]
        repository.listDelay = .milliseconds(150)
        let coordinator = IncidentMapCoordinator(
            incidentRepository: repository, policeRepository: nil, currentUid: "me"
        )
        let surface = Self.surface()

        let refresh = Task { await coordinator.refresh(surface: surface) }
        try? await Task.sleep(for: .milliseconds(30))
        await coordinator.report(.hazard, at: MapPoint(longitude: 12.07, latitude: 57.48))
        await refresh.value

        XCTAssertEqual(coordinator.incidents.map(\.id), ["incident"])
    }

    @MainActor
    func testRefreshStartedDuringPoliceReportCannotDropReturnedPin() async {
        let reportGate = AsyncGate()
        let listGate = AsyncGate()
        let police = FakePoliceRepository(reportGate: reportGate, listGate: listGate)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = IncidentMapCoordinator(
            incidentRepository: FakeIncidentRepository(), policeRepository: police,
            currentUid: "me", pollInterval: .seconds(60), fixWaitTimeout: .seconds(1)
        )
        // Let the initial polling pass observe no camera/fix and return before
        // providing the location used by this explicitly controlled refresh.
        let surface = StubMapSurface(initialState: .loaded, autoLoad: false)
        coordinator.start(surface: surface, provider: provider)
        defer { coordinator.stop() }
        await Task.yield()
        provider.emitFix(Self.fix())
        await waitUntil { coordinator.latestFix != nil }

        let reporting = Task { await coordinator.reportPoliceAtCurrentLocation() }
        await reportGate.waitUntilEntered()

        // The refresh snapshots the empty server response after the mutation
        // has begun, then remains suspended until the report has committed.
        let refresh = Task { await coordinator.refresh(surface: surface) }
        await listGate.waitUntilEntered()
        await reportGate.open()
        let reported = await reporting.value
        XCTAssertTrue(reported)
        XCTAssertEqual(coordinator.policeReports.map(\.id), ["police"])

        await listGate.open()
        await refresh.value

        XCTAssertEqual(coordinator.policeReports.map(\.id), ["police"])
        XCTAssertEqual(surface.incidentMarkers.map(\.id), ["police:police"])
    }

    @MainActor
    func testDisabledLayerUploadsReportsWithoutPresentingThem() async {
        let incident = FakeIncidentRepository()
        let police = FakePoliceRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = IncidentMapCoordinator(
            incidentRepository: incident, policeRepository: police, currentUid: "me",
            fixWaitTimeout: .seconds(1)
        )
        coordinator.setTrafficAlertsEnabled(false)
        coordinator.start(
            surface: StubMapSurface(initialState: .loaded, autoLoad: false),
            provider: provider
        )
        defer { coordinator.stop() }
        provider.emitFix(Self.fix())
        await waitUntil { coordinator.latestFix != nil }

        await coordinator.report(.hazard, at: MapPoint(longitude: 12.07, latitude: 57.48))
        let policeReported = await coordinator.reportPoliceAtCurrentLocation()

        XCTAssertEqual(incident.reportCalls, 1)
        XCTAssertEqual(police.reportSources, ["convoy"])
        XCTAssertTrue(policeReported)
        XCTAssertTrue(coordinator.incidents.isEmpty)
        XCTAssertTrue(coordinator.policeReports.isEmpty)
    }

    @MainActor
    func testLayerToggleRoundTripDiscardsInFlightIncidentReport() async {
        let reportGate = AsyncGate()
        let repository = FakeIncidentRepository(reportGate: reportGate)
        let coordinator = IncidentMapCoordinator(
            incidentRepository: repository, policeRepository: nil, currentUid: "me"
        )

        let reporting = Task {
            await coordinator.report(
                .hazard, at: MapPoint(longitude: 12.07, latitude: 57.48)
            )
        }
        await reportGate.waitUntilEntered()
        coordinator.setTrafficAlertsEnabled(false)
        coordinator.setTrafficAlertsEnabled(true)
        await reportGate.open()
        await reporting.value

        XCTAssertEqual(repository.reportCalls, 1)
        XCTAssertTrue(coordinator.incidents.isEmpty)
        XCTAssertNil(coordinator.feedback)
    }

    @MainActor
    func testDisablingLayerDiscardsInFlightPoliceReport() async {
        let reportGate = AsyncGate()
        let police = FakePoliceRepository(reportGate: reportGate)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = IncidentMapCoordinator(
            incidentRepository: FakeIncidentRepository(), policeRepository: police,
            currentUid: "me", pollInterval: .seconds(60), fixWaitTimeout: .seconds(1)
        )
        let surface = StubMapSurface(initialState: .loaded, autoLoad: false)
        coordinator.start(surface: surface, provider: provider)
        defer { coordinator.stop() }
        await Task.yield()
        provider.emitFix(Self.fix())
        await waitUntil { coordinator.latestFix != nil }

        let reporting = Task { await coordinator.reportPoliceAtCurrentLocation() }
        await reportGate.waitUntilEntered()
        coordinator.setTrafficAlertsEnabled(false)
        await reportGate.open()
        let reported = await reporting.value

        XCTAssertTrue(reported)
        XCTAssertEqual(police.reportSources, ["convoy"])
        XCTAssertTrue(coordinator.policeReports.isEmpty)
        XCTAssertTrue(surface.incidentMarkers.isEmpty)
        XCTAssertNil(coordinator.feedback)
    }

    @MainActor
    func testDisablingTrafficAlertsClearsMarkersAndSuppressesPolling() async {
        let repository = FakeIncidentRepository()
        repository.nearby = [Self.incident(id: "imported", type: .roadwork, source: "trafikverket")]
        let coordinator = IncidentMapCoordinator(
            incidentRepository: repository, policeRepository: nil, currentUid: "me",
            pollInterval: .seconds(60)
        )
        let surface = Self.surface()
        coordinator.start(surface: surface, provider: StubLocationProvider(authorization: .whileInUse))
        defer { coordinator.stop() }
        // start() owns the initial refresh. Launching a second refresh here
        // races the latest-refresh-wins generation guard: the explicit call
        // can correctly return after being superseded but before the polling
        // refresh publishes its markers. Wait for that owned poll instead.
        await waitUntil { !surface.incidentMarkers.isEmpty }
        let callsBeforeDisable = repository.listCalls

        coordinator.setTrafficAlertsEnabled(false)
        await coordinator.refresh(surface: surface)

        XCTAssertTrue(surface.incidentMarkers.isEmpty)
        XCTAssertTrue(coordinator.incidents.isEmpty)
        XCTAssertEqual(repository.listCalls, callsBeforeDisable)
    }

    func testTrafikverketRowsAreIdentifiedForVisibleAttribution() {
        XCTAssertTrue(Self.incident(id: "tv", type: .roadwork, source: "trafikverket").isImported)
        XCTAssertFalse(Self.incident(id: "member", type: .roadwork).isImported)
        XCTAssertTrue(IncidentAttribution.markerIsVisible(
            MapScreenPoint(x: 50, y: 75, trustworthy: true), width: 100, height: 100
        ))
        XCTAssertFalse(IncidentAttribution.markerIsVisible(
            MapScreenPoint(x: 50, y: 75, trustworthy: false), width: 100, height: 100
        ))
        XCTAssertFalse(IncidentAttribution.markerIsVisible(
            MapScreenPoint(x: 150, y: 75, trustworthy: true), width: 100, height: 100
        ))
    }

    func testDisabledLayerPresentationHidesMarkersAndAttributionDefensively() {
        let imported = Self.incident(
            id: "tv", type: .roadwork, source: "trafikverket"
        )
        let police = Self.police(id: "police")

        XCTAssertTrue(IncidentLayerPresentation.markers(
            enabled: false, incidents: [imported], policeReports: [police]
        ).isEmpty)
        XCTAssertTrue(IncidentLayerPresentation.importedIncidents(
            enabled: false, incidents: [imported]
        ).isEmpty)
        XCTAssertEqual(IncidentLayerPresentation.markers(
            enabled: true, incidents: [imported], policeReports: [police]
        ).map(\.id), ["tv", "police:police"])
        XCTAssertEqual(IncidentLayerPresentation.importedIncidents(
            enabled: true, incidents: [imported]
        ).map(\.id), ["tv"])
    }

    @MainActor
    private func waitUntil(
        timeout: TimeInterval = 2,
        _ condition: @escaping @MainActor () -> Bool,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return }
            await Task.yield()
            try? await Task.sleep(for: .milliseconds(1))
        }
        XCTFail("Condition was not met", file: file, line: line)
    }

    private static func incident(
        id: String,
        type: IncidentType,
        latitude: Double = 57.48,
        longitude: Double = 12.07,
        source: String = "user"
    ) -> RoadIncident {
        RoadIncident(
            id: id, type: type, longitude: longitude, latitude: latitude,
            note: nil, source: source, reporterUid: "reporter",
            createdAt: nil, postedAt: nil, confirmationCount: 0,
            clearedCount: 0, reportedCleared: false
        )
    }

    private static func fix(
        timestamp: Date = Date(), latitude: Double = 57.48, longitude: Double = 12.07
    ) -> LocationFix {
        LocationFix.of(latitude: latitude, longitude: longitude, timestamp: timestamp)!
    }

    @MainActor
    private static func surface() -> StubMapSurface {
        let surface = StubMapSurface(initialState: .loaded, autoLoad: false)
        surface.setCameraSnapshotForTest(MapCameraSnapshot(
            latitude: 57.48, longitude: 12.07, zoom: 15, bearing: 0, pitch: 0
        ))
        return surface
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
        private(set) var listCalls = 0
        var nearby: [RoadIncident] = []
        var listDelay: Duration?
        private let reportGate: AsyncGate?

        init(reportGate: AsyncGate? = nil) {
            self.reportGate = reportGate
        }

        func report(type: IncidentType, at point: MapPoint, note: String?) async throws -> RoadIncident {
            lock.withLock { reportCalls += 1 }
            if let reportGate { await reportGate.wait() }
            return IncidentMapTests.incident(
                id: "incident", type: type, latitude: point.latitude, longitude: point.longitude
            )
        }

        func listNearby(center: MapPoint, radiusMeters: Double) async throws -> [RoadIncident] {
            lock.withLock { listCalls += 1 }
            if let listDelay { try await Task.sleep(for: listDelay) }
            return nearby
        }
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
        var nearby: [PoliceReport] = []
        private let reportGate: AsyncGate?
        private let listGate: AsyncGate?

        init(reportGate: AsyncGate? = nil, listGate: AsyncGate? = nil) {
            self.reportGate = reportGate
            self.listGate = listGate
        }

        func report(at point: MapPoint, source: String) async throws -> PoliceReport {
            lock.withLock { reportSources.append(source) }
            if let reportGate { await reportGate.wait() }
            return IncidentMapTests.police(
                id: "police", latitude: point.latitude, longitude: point.longitude
            )
        }
        func listNearby(center: MapPoint, radiusMeters: Double) async throws -> [PoliceReport] {
            let snapshot = nearby
            if let listGate { await listGate.wait() }
            return snapshot
        }
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

    private actor AsyncGate {
        private var entered = false
        private var isOpen = false
        private var gateContinuations: [CheckedContinuation<Void, Never>] = []
        private var entryContinuations: [CheckedContinuation<Void, Never>] = []

        func wait() async {
            entered = true
            let entryContinuations = self.entryContinuations
            self.entryContinuations.removeAll()
            entryContinuations.forEach { $0.resume() }
            guard !isOpen else { return }
            await withCheckedContinuation { gateContinuations.append($0) }
        }

        func waitUntilEntered() async {
            guard !entered else { return }
            await withCheckedContinuation { entryContinuations.append($0) }
        }

        func open() {
            isOpen = true
            let gateContinuations = self.gateContinuations
            self.gateContinuations.removeAll()
            gateContinuations.forEach { $0.resume() }
        }
    }
}
