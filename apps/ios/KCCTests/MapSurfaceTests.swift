import XCTest

@testable import KCC

/// Unit tests for the map-surface seam and its stub implementation — the iOS
/// port of the `StubMapSurface` cases in Android's `ShellNavTest.kt` (traffic
/// toggle, day/night mode, 3D/2D), plus coverage for the seam's pure types
/// and the stub's recorded-wiring hooks.
@MainActor
final class MapSurfaceTests: XCTestCase {

    // MARK: - layer toggles (ported from Android's ShellNavTest)

    func testStubSurfaceStartsWithTrafficOffAndToggles() {
        let surface = StubMapSurface(autoLoad: false)
        XCTAssertFalse(surface.trafficEnabled)
        surface.setTrafficEnabled(true)
        XCTAssertTrue(surface.trafficEnabled)
        surface.setTrafficEnabled(false)
        XCTAssertFalse(surface.trafficEnabled)
    }

    func testStubSurfaceStartsInDayModeAndTogglesDayNight() {
        let surface = StubMapSurface(autoLoad: false)
        XCTAssertEqual(surface.mapMode, .day)
        surface.setMapMode(.night)
        XCTAssertEqual(surface.mapMode, .night)
        surface.setMapMode(.day)
        XCTAssertEqual(surface.mapMode, .day)
    }

    func testStubSurfaceStartsIn3DAndToggles3D2D() {
        let surface = StubMapSurface(autoLoad: false)
        XCTAssertTrue(surface.is3D)
        surface.set3DEnabled(false)
        XCTAssertFalse(surface.is3D)
        surface.set3DEnabled(true)
        XCTAssertTrue(surface.is3D)
    }

    // MARK: - stub defaults

    func testStubSurfaceDefaultsMatchTheAndroidStub() {
        let surface = StubMapSurface(autoLoad: false)
        XCTAssertEqual(surface.loadState, .loading)
        XCTAssertNil(surface.userMarker)
        XCTAssertEqual(surface.bearing, 0)
        XCTAssertNil(surface.routeOverlay)
        XCTAssertEqual(surface.incidentMarkers, [])
        XCTAssertEqual(surface.eventMarkers, [])
        XCTAssertEqual(surface.crownMarkers, [])
        XCTAssertEqual(surface.billboardMarkers, [])
        XCTAssertNil(surface.placeRequest)
        XCTAssertNil(surface.cameraSnapshot)
        // The stub starts ACTIVE (the shell opens on the Map tab) and
        // north-up, with the untouched-slider resting zoom.
        XCTAssertTrue(surface.isActive)
        XCTAssertEqual(surface.compassMode, .northUp)
        XCTAssertEqual(surface.browsingZoom, 16.0, accuracy: 1e-9)
    }

    // MARK: - load state

    func testAutoLoadFalsePinsTheLoadState() async {
        let surface = StubMapSurface(autoLoad: false)
        await surface.simulateInitialLoadIfNeeded()
        XCTAssertEqual(surface.loadState, .loading)
    }

    func testAutoLoadSimulatesTheTileLoadOnce() async {
        let surface = StubMapSurface()
        XCTAssertEqual(surface.loadState, .loading)
        // .zero delay keeps the suite fast; production callers use the
        // default 700 ms.
        await surface.simulateInitialLoadIfNeeded(delay: .zero)
        XCTAssertEqual(surface.loadState, .loaded)
        // A second call is a no-op: the simulation only runs while the state
        // is still `.loading`. The long delay proves it returns immediately
        // rather than re-running the load.
        await surface.simulateInitialLoadIfNeeded(delay: .seconds(60))
        XCTAssertEqual(surface.loadState, .loaded)
    }

    func testSimulateInitialLoadIsANoOpWhenAlreadyLoaded() async {
        let surface = StubMapSurface(initialState: .loaded)
        await surface.simulateInitialLoadIfNeeded(delay: .seconds(60))
        XCTAssertEqual(surface.loadState, .loaded)
    }

    func testCancellingTheSimulatedLoadLeavesTheStateUntouched() async {
        // The doc contract: cancelling the surrounding task before the delay
        // elapses leaves the state untouched — mirroring Android's cancelled
        // LaunchedEffect.
        let surface = StubMapSurface()
        let load = Task { await surface.simulateInitialLoadIfNeeded(delay: .seconds(60)) }
        load.cancel()
        await load.value
        XCTAssertEqual(surface.loadState, .loading)
    }

    func testMarkLoadedForcesTheLoadedState() {
        let surface = StubMapSurface(autoLoad: false)
        surface.markLoaded()
        XCTAssertEqual(surface.loadState, .loaded)
    }

    func testExplicitInitialStateIsRespected() {
        let surface = StubMapSurface(initialState: .loaded, autoLoad: false)
        XCTAssertEqual(surface.loadState, .loaded)
    }

    // MARK: - place-request gestures (one slot for both gestures)

    func testLongPressRaisesANamelessPlaceRequest() {
        let surface = StubMapSurface(autoLoad: false)
        let point = MapPoint(longitude: 12.07, latitude: 57.48)
        surface.emitLongPress(point)
        XCTAssertEqual(surface.placeRequest, MapPlaceRequest(point: point, name: nil))
    }

    func testPlaceTapRaisesANamedPlaceRequestInTheSameSlot() {
        let surface = StubMapSurface(autoLoad: false)
        surface.emitLongPress(MapPoint(longitude: 1, latitude: 2))
        let point = MapPoint(longitude: 12.08, latitude: 57.49)
        surface.emitPlaceTap(point, name: "Kungsbacka Torg")
        // The later gesture REPLACES the pending one — one slot, one pending
        // confirmation, never two.
        XCTAssertEqual(
            surface.placeRequest,
            MapPlaceRequest(point: point, name: "Kungsbacka Torg")
        )
        surface.consumePlaceRequest()
        XCTAssertNil(surface.placeRequest)
    }

    // MARK: - marker taps (four separate slots, so ids can't cross layers)

    func testMarkerTapSlotsAreSeparatePerLayer() {
        let surface = StubMapSurface(autoLoad: false)
        surface.emitIncidentTap("incident-1")
        surface.emitEventTap("event-1")
        surface.emitCrownTap("crown-1")
        surface.emitBillboardTap("billboard-1")
        XCTAssertEqual(surface.incidentTap, "incident-1")
        XCTAssertEqual(surface.eventTap, "event-1")
        XCTAssertEqual(surface.crownTap, "crown-1")
        XCTAssertEqual(surface.billboardTap, "billboard-1")
        // Consuming one slot must not clear the others.
        surface.consumeIncidentTap()
        XCTAssertNil(surface.incidentTap)
        XCTAssertEqual(surface.eventTap, "event-1")
        surface.consumeEventTap()
        surface.consumeCrownTap()
        surface.consumeBillboardTap()
        XCTAssertNil(surface.eventTap)
        XCTAssertNil(surface.crownTap)
        XCTAssertNil(surface.billboardTap)
    }

    func testIncidentTapNeverRaisesAPlaceRequest() {
        // Marker taps and place gestures are separate channels — an incident
        // tap must never leak into the place-request slot (mirrors the
        // Android seam's separation).
        let surface = StubMapSurface(autoLoad: false)
        surface.emitIncidentTap("incident-1")
        XCTAssertEqual(surface.incidentTap, "incident-1")
        XCTAssertNil(surface.placeRequest)
    }

    func testASecondTapInTheSameSlotSupersedesTheFirst() {
        let surface = StubMapSurface(autoLoad: false)
        surface.emitIncidentTap("incident-1")
        surface.emitIncidentTap("incident-2")
        // One slot, one pending tap — the later gesture wins.
        XCTAssertEqual(surface.incidentTap, "incident-2")
        surface.consumeIncidentTap()
        XCTAssertNil(surface.incidentTap)
    }

    // MARK: - compass / recenter wiring

    func testRecenterNorthUpCountsAsBothARecentreAndANorthReset() {
        let surface = StubMapSurface(autoLoad: false)
        surface.recenterNorthUp()
        XCTAssertEqual(surface.recenterCount, 1)
        XCTAssertEqual(surface.resetNorthCount, 1)
        // The two single-purpose hooks stay independent.
        surface.recenter()
        XCTAssertEqual(surface.recenterCount, 2)
        XCTAssertEqual(surface.resetNorthCount, 1)
        surface.resetNorth()
        XCTAssertEqual(surface.recenterCount, 2)
        XCTAssertEqual(surface.resetNorthCount, 2)
    }

    func testSettingANewCompassModeRecentresAndCountsOnce() {
        let surface = StubMapSurface(autoLoad: false)
        surface.setCompassMode(.courseUp)
        XCTAssertEqual(surface.compassMode, .courseUp)
        XCTAssertEqual(surface.compassModeChanges, 1)
        XCTAssertEqual(surface.recenterCount, 1)
        // Switching back to north-up also resets north.
        surface.setCompassMode(.northUp)
        XCTAssertEqual(surface.compassModeChanges, 2)
        XCTAssertEqual(surface.recenterCount, 2)
        XCTAssertEqual(surface.resetNorthCount, 1)
    }

    func testRePushingTheSameCompassModeIsANoOp() {
        let surface = StubMapSurface(autoLoad: false)
        surface.setCompassMode(.northUp) // already the stub's starting mode
        XCTAssertEqual(surface.compassModeChanges, 0)
        XCTAssertEqual(surface.recenterCount, 0)
        XCTAssertEqual(surface.resetNorthCount, 0)
    }

    func testCompassModeToggledFlipsToTheOppositeMode() {
        XCTAssertEqual(MapCompassMode.northUp.toggled(), .courseUp)
        XCTAssertEqual(MapCompassMode.courseUp.toggled(), .northUp)
    }

    func testCompassModeStoredNameParsingFallsBackToTheDefault() {
        // The stored names are the exact strings Android persists.
        XCTAssertEqual(MapCompassMode.fromStoredName("NorthUp"), .northUp)
        XCTAssertEqual(MapCompassMode.fromStoredName("CourseUp"), .courseUp)
        // Unknown / absent / corrupt names must fall back, not trap.
        XCTAssertEqual(MapCompassMode.fromStoredName(nil), MapCompassMode.defaultMode)
        XCTAssertEqual(MapCompassMode.fromStoredName("garbage"), MapCompassMode.defaultMode)
        XCTAssertEqual(MapCompassMode.defaultMode, .courseUp)
    }

    // MARK: - recorded wiring hooks

    func testConvoyFitRecordsPointsAndFocusIndependently() {
        let surface = StubMapSurface(autoLoad: false)
        let points = [MapPoint(longitude: 1, latitude: 2), MapPoint(longitude: 3, latitude: 4)]
        surface.setConvoyFit(points: points, focusEnabled: true)
        XCTAssertEqual(surface.convoyFit, points)
        XCTAssertTrue(surface.convoyFocusEnabled)
        // Focus can stay ON while the fittable points transiently vanish —
        // the nil points must not read as "the user toggled focus off".
        surface.setConvoyFit(points: nil, focusEnabled: true)
        XCTAssertNil(surface.convoyFit)
        XCTAssertTrue(surface.convoyFocusEnabled)
    }

    func testCenterOnRecordsTheRequestedPoint() {
        let surface = StubMapSurface(autoLoad: false)
        let point = MapPoint(longitude: 12.1, latitude: 57.5)
        surface.centerOn(point)
        XCTAssertEqual(surface.centeredOn, point)
    }

    func testSeedBreadcrumbIgnoresAnEmptySeed() {
        let surface = StubMapSurface(autoLoad: false)
        let points = [MapPoint(longitude: 1, latitude: 2)]
        surface.seedBreadcrumb(points)
        XCTAssertEqual(surface.seededBreadcrumb, points)
        // An empty seed is a no-op — it must not clear a recorded tail.
        surface.seedBreadcrumb([])
        XCTAssertEqual(surface.seededBreadcrumb, points)
    }

    func testSetActiveRecordsTheLatestValue() {
        let surface = StubMapSurface(autoLoad: false)
        surface.setActive(false)
        XCTAssertFalse(surface.isActive)
        surface.setActive(true)
        XCTAssertTrue(surface.isActive)
    }

    func testMarkerLayerSettersReplaceTheirLayer() {
        let surface = StubMapSurface(autoLoad: false)
        let incidents = [
            MapIncidentMarker(
                id: "i1", longitude: 1, latitude: 2,
                colorArgb: 0xFFFF0000, iconName: "exclamationmark.triangle",
                glyphColorArgb: 0xFFFFFFFF
            )
        ]
        surface.setIncidentMarkers(incidents)
        XCTAssertEqual(surface.incidentMarkers, incidents)
        surface.setEventMarkers([MapEventMarker(id: "e1", longitude: 3, latitude: 4)])
        XCTAssertEqual(surface.eventMarkers.map(\.id), ["e1"])
        surface.setBillboardMarkers([MapBillboardMarker(id: "b1", longitude: 5, latitude: 6)])
        XCTAssertEqual(surface.billboardMarkers.map(\.id), ["b1"])
        // Pushing an empty array takes a layer DOWN.
        surface.setIncidentMarkers([])
        XCTAssertEqual(surface.incidentMarkers, [])
    }

    func testFollowMeTrailIsDistinctFromTheSelfBreadcrumb() {
        let surface = StubMapSurface(autoLoad: false)
        let trail = [MapPoint(longitude: 1, latitude: 2)]
        surface.setFollowMeTrail(trail)
        XCTAssertEqual(surface.followMeTrail, trail)
        XCTAssertEqual(surface.seededBreadcrumb, [])
        // Nil takes the shared trail down.
        surface.setFollowMeTrail(nil)
        XCTAssertNil(surface.followMeTrail)
    }

    // MARK: - projection / viewport (stub behaviour + test hooks)

    func testStubProjectsNothingUntilATestInstallsAProjection() {
        let surface = StubMapSurface(autoLoad: false)
        XCTAssertNil(surface.screenPositionFor(latitude: 57.48, longitude: 12.07))
        surface.setProjectionForTest { _, _ in MapScreenPoint(x: 10, y: 20) }
        XCTAssertEqual(
            surface.screenPositionFor(latitude: 57.48, longitude: 12.07),
            MapScreenPoint(x: 10, y: 20, trustworthy: true)
        )
        surface.setProjectionForTest(nil)
        XCTAssertNil(surface.screenPositionFor(latitude: 57.48, longitude: 12.07))
    }

    func testStubFiltersNonFiniteProjectionsToNil() {
        // The MapProjection contract: a non-finite projection returns nil.
        let surface = StubMapSurface(autoLoad: false)
        surface.setProjectionForTest { _, _ in MapScreenPoint(x: .nan, y: 20) }
        XCTAssertNil(surface.screenPositionFor(latitude: 57.48, longitude: 12.07))
        surface.setProjectionForTest { _, _ in MapScreenPoint(x: 10, y: .infinity) }
        XCTAssertNil(surface.screenPositionFor(latitude: 57.48, longitude: 12.07))
    }

    func testStubReportsAFixedVisibleRadiusOverridableForTests() {
        let surface = StubMapSurface(autoLoad: false)
        XCTAssertEqual(surface.visibleRadiusMeters(), 15_000)
        surface.setVisibleRadiusForTest(500)
        XCTAssertEqual(surface.visibleRadiusMeters(), 500)
    }

    func testCameraSnapshotIsPinnableForTests() {
        let surface = StubMapSurface(autoLoad: false)
        let snapshot = MapCameraSnapshot.of(
            latitude: 57.48, longitude: 12.07, zoom: 16, bearing: 0, pitch: 45
        )
        surface.setCameraSnapshotForTest(snapshot)
        XCTAssertEqual(surface.cameraSnapshot, snapshot)
    }

    // MARK: - pure types

    func testCameraSnapshotRoundsIntoADeDuplicableValue() {
        // Two frames of a settled camera differing only by float noise must
        // compare EQUAL after rounding, so observers collapse them.
        let a = MapCameraSnapshot.of(
            latitude: 57.4874001, longitude: 12.0757004,
            zoom: 16.00004, bearing: 0.4, pitch: 44.6
        )
        let b = MapCameraSnapshot.of(
            latitude: 57.4874004, longitude: 12.0756996,
            zoom: 15.99996, bearing: 0.1, pitch: 45.4
        )
        XCTAssertEqual(a, b)
        XCTAssertEqual(a.latitude, 57.4874, accuracy: 1e-9)
        XCTAssertEqual(a.zoom, 16.0, accuracy: 1e-9)
        XCTAssertEqual(a.bearing, 0, accuracy: 1e-9)
        XCTAssertEqual(a.pitch, 45, accuracy: 1e-9)
    }

    func testCameraSnapshotRoundsTiesToEvenMatchingAndroid() {
        // Kotlin's `round` resolves .5 ties to the nearest EVEN integer;
        // the snapshot must do the same so both platforms round an identical
        // camera to identical values: 42.5 -> 42 (down), 43.5 -> 44 (up).
        let snapshot = MapCameraSnapshot.of(
            latitude: 0, longitude: 0, zoom: 16, bearing: 42.5, pitch: 43.5
        )
        XCTAssertEqual(snapshot.bearing, 42, accuracy: 1e-9)
        XCTAssertEqual(snapshot.pitch, 44, accuracy: 1e-9)
    }

    func testCameraSnapshotZeroesNonFiniteComponents() {
        let snapshot = MapCameraSnapshot.of(
            latitude: .nan, longitude: .infinity, zoom: 16, bearing: 0, pitch: 45
        )
        XCTAssertEqual(snapshot.latitude, 0)
        XCTAssertEqual(snapshot.longitude, 0)
        XCTAssertEqual(snapshot.zoom, 16)
    }

    func testScreenPointDefaultsToTrustworthy() {
        // A renderer that cannot self-assess is taken at face value.
        XCTAssertTrue(MapScreenPoint(x: 1, y: 2).trustworthy)
        XCTAssertFalse(MapScreenPoint(x: 1, y: 2, trustworthy: false).trustworthy)
    }

    func testRouteOverlayDefaultsToTheSurfaceOwnBottomPadding() {
        let overlay = MapRouteOverlay(
            destination: MapPoint(longitude: 1, latitude: 2), path: []
        )
        XCTAssertNil(overlay.bottomInsetPx)
    }
}
