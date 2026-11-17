import XCTest
@testable import KCC

@MainActor
final class RoadCameraTests: XCTestCase {
    private func directory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("RoadCameraTests-" + UUID().uuidString)
    }

    private func siteCamera(_ id: String, direction: Double?, latitude: Double = 57.45,
                            type: RoadCameraType = .roadCondition, name: String = "Kungsbacka Syd") -> RoadCamera {
        RoadCamera(id: id, type: type, name: name, latitude: latitude, longitude: 12.09, direction: direction)
    }

    func testDirectionsShareOneStableMarkerIndependentOfCatalogOrder() {
        let se = siteCamera("b", direction: 135)
        let nw = siteCamera("a", direction: 315, latitude: 57.4501)
        let locations = RoadCameraLocation.group([nw, se])
        XCTAssertEqual(locations, RoadCameraLocation.group([se, nw]))
        XCTAssertEqual(locations.count, 1)
        XCTAssertEqual(locations[0].views.map(\.id), ["b", "a"])
        XCTAssertEqual(locations[0].camera, se)
    }

    func testOppositeSpeedCameraPairKeepsItsRealAnchorAndDirectionsAtHighZoom() {
        let first = siteCamera("first", direction: 135, type: .speed)
        let opposite = siteCamera("opposite", direction: 315, latitude: 57.4508, type: .speed)
        let sites = RoadCameraLocation.group([opposite, first])
        XCTAssertEqual(sites.count, 1)
        XCTAssertEqual(sites, RoadCameraLocation.group([first, opposite]))
        let groups = RoadCameraMapGroup.group(sites, zoom: 20)
        XCTAssertEqual(groups.count, 1)
        XCTAssertTrue(groups[0].opposingDirections)
        XCTAssertEqual(groups[0].views, [first, opposite])
        XCTAssertTrue(groups[0].views.contains(groups[0].camera))
    }

    func testProximityAloneNeverMakesUnverifiedSpeedCamerasAnOppositeDirectionSite() {
        let first = siteCamera("a", direction: 135, type: .speed)
        for other in [siteCamera("b", direction: 135, type: .speed),
            siteCamera("b", direction: nil, type: .speed),
            siteCamera("b", direction: 315, type: .speed, name: "Other road"),
            siteCamera("b", direction: 315, latitude: 57.452, type: .speed)] {
            XCTAssertEqual(RoadCameraLocation.group([first, other]).count, 2)
        }
    }

    func testZoomGroupsPreserveAllCamerasAndKeepLayerTypesSeparate() {
        let cameras = RoadCameraType.allCases.flatMap { type in
            (0...3).map { i in siteCamera("\(type.rawValue)-\(i)", direction: 135,
                latitude: 57.45 + Double(i) * 0.0001, type: type, name: "Site \(i)") }
        }
        let sites = RoadCameraLocation.group(cameras)
        let groups = RoadCameraMapGroup.group(sites, zoom: 13)
        XCTAssertEqual(groups.count, 3)
        XCTAssertEqual(groups.map { $0.views.count }, [4, 4, 4])
        XCTAssertEqual(groups.flatMap(\.views).map(\.id).sorted(), cameras.map(\.id).sorted())
        XCTAssertTrue(groups.allSatisfy { Set($0.views.map(\.type)).count == 1 })
        XCTAssertEqual(groups, RoadCameraMapGroup.group(Array(sites.reversed()), zoom: 13.9))
        XCTAssertEqual(RoadCameraMapGroup.group(sites, zoom: 17).count, 12)
    }

    func testZoomGroupsCannotJoinProximityChainsAcrossGridCells() {
        let sites = (0...2).map { i in RoadCameraLocation(views: [
            siteCamera("\(i)", direction: 135, latitude: 57.45 + Double(i) * 0.002, name: "Site \(i)")]) }
        let groups = RoadCameraMapGroup.group(sites, zoom: 13)
        XCTAssertEqual(groups.map { $0.views.count }, [2, 1])
        XCTAssertEqual(groups[0].camera, sites[0].camera)
        XCTAssertFalse(groups[0].opposingDirections)
        XCTAssertTrue(RoadCameraMapGroup.group([], zoom: 13).isEmpty)
        XCTAssertEqual(groups, RoadCameraMapGroup.group(sites, zoom: 13.99))
    }

    func testVisibleGroupsCountBeforeTheDrawCapAndSelectingDoesNotFetchPhotos() async {
        let upstream = CameraStub()
        upstream.catalogCameras = (0..<300).map { i in
            siteCamera("camera-\(i)", direction: 135, latitude: 57.45 + Double(i) * 0.000001,
                type: .speed, name: "Site \(i)")
        }
        let coordinator = RoadCameraMapCoordinator(repository: upstream)
        await coordinator.refresh(enabledTypes: [.speed])
        let center = MapCameraSnapshot(latitude: 57.45, longitude: 12.09, zoom: 13, bearing: 0, pitch: 0)
        let groups = coordinator.visibleGroups(center: center, radius: 15_000)
        XCTAssertEqual(groups.count, 1)
        XCTAssertEqual(groups[0].views.count, 300)
        XCTAssertFalse(groups[0].opposingDirections)
        coordinator.selectGroup(groups[0])
        XCTAssertEqual(coordinator.selectedViews.count, 300)
        coordinator.selectDirection(groups[0].views.last!)
        XCTAssertEqual(coordinator.selectedCamera?.id, groups[0].views.last?.id)
        XCTAssertEqual(coordinator.selectedViews.count, 300)
        let close = MapCameraSnapshot(latitude: 57.45, longitude: 12.09, zoom: 17, bearing: 0, pitch: 0)
        XCTAssertEqual(coordinator.visibleGroups(center: close, radius: 15_000).count, 250)
        XCTAssertEqual(upstream.catalogCalls, 1)
        XCTAssertEqual(upstream.photoCalls, 0)
        await coordinator.refresh(enabledTypes: [])
        XCTAssertTrue(coordinator.visibleGroups(center: center, radius: 15_000).isEmpty)
        XCTAssertNil(coordinator.selectedCamera)
    }

    func testCoordinatedDensityKeepsAllSitesBeyondTheLegacyDrawCap() async {
        let upstream = CameraStub()
        upstream.catalogCameras = (0..<300).map { i in
            siteCamera("camera-\(i)", direction: 135, latitude: 57.45 + Double(i) * 0.000001,
                type: .speed, name: "Site \(i)")
        }
        let coordinator = RoadCameraMapCoordinator(repository: upstream)
        await coordinator.refresh(enabledTypes: [.speed])
        let center = MapCameraSnapshot(latitude: 57.45, longitude: 12.09, zoom: 13, bearing: 0, pitch: 0)
        let sites = coordinator.visibleGroups(center: center, radius: nil, coordinatedDensity: true)
        XCTAssertEqual(sites.count, 300)
        XCTAssertEqual(Set(sites.flatMap(\.views).map(\.id)), Set((upstream.catalogCameras ?? []).map(\.id)))
        XCTAssertEqual(upstream.photoCalls, 0)
    }

    func testAnOpenGroupRetainsSelectionAcrossZoomAndHonorsCatalogRemoval() async {
        let upstream = CameraStub()
        let first = siteCamera("a", direction: 135, type: .traffic, name: "First site")
        let second = siteCamera("b", direction: 315, latitude: 57.4508, type: .traffic, name: "Second site")
        upstream.catalogCameras = [first, second]
        let coordinator = RoadCameraMapCoordinator(repository: upstream)
        await coordinator.refresh(enabledTypes: [.traffic])
        let overview = MapCameraSnapshot(latitude: 57.45, longitude: 12.09, zoom: 13, bearing: 0, pitch: 0)
        coordinator.selectGroup(coordinator.visibleGroups(center: overview, radius: nil)[0])
        coordinator.selectDirection(second)
        let close = MapCameraSnapshot(latitude: 57.45, longitude: 12.09, zoom: 17, bearing: 0, pitch: 0)
        XCTAssertEqual(coordinator.visibleGroups(center: close, radius: nil).count, 2)
        XCTAssertEqual(coordinator.selectedCamera?.id, second.id)
        XCTAssertEqual(coordinator.selectedViews, [first, second])
        upstream.catalogCameras = [second]
        await coordinator.refresh(enabledTypes: [.traffic])
        XCTAssertEqual(coordinator.selectedViews, [second])
        XCTAssertEqual(coordinator.selectedCamera?.id, second.id)
        upstream.catalogCameras = []
        await coordinator.refresh(enabledTypes: [.traffic])
        XCTAssertNil(coordinator.selectedCamera)
        XCTAssertTrue(coordinator.selectedViews.isEmpty)
        XCTAssertEqual(upstream.photoCalls, 0)
    }

    func testOpenPairReconcilesChangedBearingWithoutAddingNewMembersOrFetchingPhotos() async {
        let upstream = CameraStub()
        let first = siteCamera("a", direction: 135, type: .speed)
        let opposite = siteCamera("b", direction: 315, type: .speed)
        upstream.catalogCameras = [first, opposite]
        let coordinator = RoadCameraMapCoordinator(repository: upstream)
        await coordinator.refresh(enabledTypes: [.speed])
        let center = MapCameraSnapshot(latitude: 57.45, longitude: 12.09, zoom: 13, bearing: 0, pitch: 0)
        coordinator.selectGroup(coordinator.visibleGroups(center: center, radius: nil)[0])
        XCTAssertTrue(coordinator.selectedOpposingDirections)
        let changed = siteCamera("b", direction: 135, type: .speed)
        upstream.catalogCameras = [first, changed, siteCamera("c", direction: 315, type: .speed)]
        await coordinator.refresh(enabledTypes: [.speed])
        XCTAssertEqual(coordinator.selectedViews.map(\.id), ["a", "b"])
        XCTAssertFalse(coordinator.selectedOpposingDirections)
        XCTAssertEqual(coordinator.selectedGroup?.sites.count, 2)
        XCTAssertEqual(coordinator.selectedCamera?.id, "a")
        XCTAssertEqual(upstream.photoCalls, 0)
    }

    func testOnlyNearbyPhotoCamerasWithSameNameAndTypeShareAMarker() {
        let cameras = [siteCamera("a", direction: 135),
            siteCamera("b", direction: 315, name: " kungsbacka syd "),
            siteCamera("distant", direction: 135, latitude: 57.46),
            siteCamera("other-name", direction: 135, name: "Kungsbacka Nord"),
            siteCamera("traffic", direction: 135, type: .traffic),
            siteCamera("speed1", direction: 135, type: .speed),
            siteCamera("speed2", direction: 315, type: .speed)]
        XCTAssertEqual(RoadCameraLocation.group(cameras).count, 5)
        XCTAssertEqual(RoadCameraLocation.group([
            siteCamera("c", direction: 135, latitude: 57.4506),
            siteCamera("b", direction: 135, latitude: 57.4503),
            siteCamera("a", direction: 135)]).map { $0.views.count }, [2, 1])
    }

    func testDirectionLabelsWrapAtNorthAndDisambiguateUnknownOrMatchingBearings() {
        XCTAssertEqual(siteCamera("a", direction: 360).compassIndex, 0)
        XCTAssertEqual(siteCamera("a", direction: 359).compassIndex, 0)
        XCTAssertEqual(siteCamera("a", direction: 22.5).compassIndex, 1)
        XCTAssertEqual(siteCamera("a", direction: 135).compassIndex, 3)
        XCTAssertEqual(siteCamera("a", direction: 315).compassIndex, 7)
        let invalidBearings: [Double?] = [nil, -1, 361, .nan]
        for bearing in invalidBearings {
            XCTAssertNil(siteCamera("a", direction: bearing).compassIndex)
        }
        let views = RoadCameraLocation.group([siteCamera("unknown", direction: nil),
            siteCamera("b", direction: 5), siteCamera("a", direction: 360)])[0].views
        XCTAssertEqual(views.map(\.id), ["a", "b", "unknown"])
        XCTAssertEqual(Set(views.map { $0.viewLabel(in: views) }).count, 3)
    }

    func testChangingDirectionLoadsOnlySelectedViewAndRejectsPreviousPhoto() async {
        let upstream = CameraStub()
        let se = siteCamera("se", direction: 135)
        let nw = siteCamera("nw", direction: 315)
        upstream.catalogCameras = [nw, se]
        upstream.suspendPhotos = true
        let coordinator = RoadCameraMapCoordinator(repository: upstream)
        await coordinator.refresh(enabledTypes: [.roadCondition])
        XCTAssertEqual(coordinator.locations.count, 1)
        coordinator.select(coordinator.locations[0].camera)
        XCTAssertEqual(coordinator.selectedViews, [se, nw])
        XCTAssertEqual(upstream.photoCalls, 0)
        let first = Task { await coordinator.loadPhoto(cameraId: se.id) }
        await waitForPendingPhotos(1, in: upstream)
        coordinator.selectDirection(nw)
        XCTAssertEqual(coordinator.selectedCamera, nw)
        XCTAssertNil(coordinator.photo)
        let second = Task { await coordinator.loadPhoto(cameraId: nw.id) }
        await waitForPendingPhotos(2, in: upstream)
        XCTAssertEqual(upstream.requestedCameraIds, ["se", "nw"])
        upstream.completeFirstPhoto()
        await first.value
        XCTAssertNil(coordinator.photo)
        XCTAssertTrue(coordinator.photoLoading)
        upstream.completeFirstPhoto()
        await second.value
        XCTAssertNotNil(coordinator.photo)
        coordinator.selectDirection(nw)
        XCTAssertNotNil(coordinator.photo, "tapping the active direction should keep its loaded image")
        coordinator.selectDirection(upstream.traffic)
        XCTAssertEqual(coordinator.selectedCamera, nw, "direction switching stays within the selected site")
    }

    func testSpeedAndCombinedPhotoLayersStartOffAndPersistIndependently() {
        let suite = "RoadCameraTests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = MapLayerPreferences(defaults: defaults)
        XCTAssertTrue(preferences.cameraLayers.isEmpty)
        preferences.setCameraLayer(.speed, enabled: true)
        preferences.setCameraLayer(.traffic, enabled: true)
        XCTAssertEqual(preferences.cameraLayers, [.speed, .traffic, .roadCondition])
        XCTAssertEqual(MapLayerPreferences(defaults: defaults).cameraLayers, [.speed, .traffic, .roadCondition])
        preferences.setCameraLayer(.roadCondition, enabled: false)
        XCTAssertEqual(preferences.cameraLayers, [.speed])
        XCTAssertEqual(MapLayerPreferences(defaults: defaults).cameraLayers, [.speed])
        XCTAssertFalse(MapLayerPreferences(defaults: defaults).trafficEnabled)
    }

    func testEitherLegacyPhotoLayerEnablesBothTypesAndCombinedChoiceSurvivesRestart() {
        let suite = "RoadCameraTests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        for traffic in [false, true] {
            for roadCondition in [false, true] {
                defaults.removePersistentDomain(forName: suite)
                defaults.set(traffic, forKey: "map.layers.camera.traffic")
                defaults.set(roadCondition, forKey: "map.layers.camera.road_condition")
                let preferences = MapLayerPreferences(defaults: defaults)
                let expected: Set<RoadCameraType> = traffic || roadCondition ? [.traffic, .roadCondition] : []
                XCTAssertEqual(preferences.cameraLayers, expected)
                preferences.setCameraLayer(.roadCondition, enabled: true)
                XCTAssertEqual(MapLayerPreferences(defaults: defaults).cameraLayers, [.traffic, .roadCondition])
                preferences.setCameraLayer(.traffic, enabled: false)
                XCTAssertTrue(MapLayerPreferences(defaults: defaults).cameraLayers.isEmpty)
            }
        }
    }

    func testCatalogPersistsFor24HoursAndFallsBackOffline() async throws {
        let location = directory()
        defer { try? FileManager.default.removeItem(at: location) }
        let upstream = CameraStub()
        var now = Date(timeIntervalSince1970: 10_000)
        let cache = CachedRoadCameraRepository(upstream: upstream, directory: location, now: { now })
        _ = try await cache.catalog()
        let restored = CachedRoadCameraRepository(upstream: upstream, directory: location, now: { now })
        _ = try await restored.catalog()
        XCTAssertEqual(upstream.catalogCalls, 1)
        now = now.addingTimeInterval(86_400)
        upstream.failure = KccFunctionsError(code: .unavailable)
        let fallback = try await restored.catalog()
        XCTAssertEqual(fallback.cameras.count, 2)
        XCTAssertEqual(upstream.catalogCalls, 2)
        _ = try await restored.catalog()
        XCTAssertEqual(upstream.catalogCalls, 2, "offline toggles must not hammer the callable")
    }

    func testPhotoDiskCacheExpiresAfterFiveMinutes() async throws {
        let location = directory()
        defer { try? FileManager.default.removeItem(at: location) }
        let upstream = CameraStub()
        var now = Date(timeIntervalSince1970: 10_000)
        let cache = CachedRoadCameraRepository(upstream: upstream, directory: location, now: { now })
        _ = try await cache.photo(cameraId: "traffic")
        let restored = CachedRoadCameraRepository(upstream: upstream, directory: location, now: { now })
        _ = try await restored.photo(cameraId: "traffic")
        XCTAssertEqual(upstream.photoCalls, 1)
        now = now.addingTimeInterval(300)
        _ = try await restored.photo(cameraId: "traffic")
        XCTAssertEqual(upstream.photoCalls, 2)
    }

    func testConcurrentPhotoRequestsCoalesce() async throws {
        let location = directory()
        defer { try? FileManager.default.removeItem(at: location) }
        let upstream = CameraStub()
        let cache = CachedRoadCameraRepository(upstream: upstream, directory: location)
        let first = Task { try await cache.photo(cameraId: "traffic") }
        let second = Task { try await cache.photo(cameraId: "traffic") }
        _ = try await first.value
        _ = try await second.value
        XCTAssertEqual(upstream.photoCalls, 1)
    }

    func testSeventhRemotePhotoIsLocallyThrottledButCachedPhotoRemainsAvailable() async throws {
        let location = directory()
        defer { try? FileManager.default.removeItem(at: location) }
        let upstream = CameraStub()
        let now = Date(timeIntervalSince1970: 10_000)
        let cache = CachedRoadCameraRepository(upstream: upstream, directory: location, now: { now })
        for index in 0..<6 { _ = try await cache.photo(cameraId: "camera-\(index)") }
        do {
            _ = try await cache.photo(cameraId: "camera-6")
            XCTFail("seventh remote request should be blocked locally")
        } catch let error as KccFunctionsError { XCTAssertEqual(error.code, .resourceExhausted) }
        _ = try await cache.photo(cameraId: "camera-0")
        XCTAssertEqual(upstream.photoCalls, 6)
    }

    func testServerQuotaBackoffAlsoStopsOtherCameraRequests() async throws {
        let location = directory()
        defer { try? FileManager.default.removeItem(at: location) }
        let upstream = CameraStub()
        upstream.failure = KccFunctionsError(code: .resourceExhausted, retryAfterMilliseconds: 3_600_000)
        var now = Date(timeIntervalSince1970: 10_000)
        let cache = CachedRoadCameraRepository(upstream: upstream, directory: location, now: { now })
        _ = try? await cache.photo(cameraId: "first")
        now = now.addingTimeInterval(61)
        _ = try? await cache.photo(cameraId: "second")
        XCTAssertEqual(upstream.photoCalls, 1)
        now = now.addingTimeInterval(3_600)
        upstream.failure = nil
        _ = try await cache.photo(cameraId: "second")
        XCTAssertEqual(upstream.photoCalls, 2)
    }

    func testPhotoCacheEvictsOldestFilesAndStaysWithinBudget() async throws {
        let location = directory()
        defer { try? FileManager.default.removeItem(at: location) }
        let upstream = CameraStub()
        var now = Date(timeIntervalSince1970: 10_000)
        let cache = CachedRoadCameraRepository(upstream: upstream, directory: location, now: { now })
        for index in 0..<25 {
            _ = try await cache.photo(cameraId: "camera-\(index)")
            now = now.addingTimeInterval(61)
        }
        let photos = try FileManager.default.contentsOfDirectory(at: location,
            includingPropertiesForKeys: [.fileSizeKey]).filter { $0.lastPathComponent.hasPrefix("photo-") }
        XCTAssertEqual(photos.count, 20)
        let bytes = photos.reduce(0) { $0 + ((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        XCTAssertLessThanOrEqual(bytes, RoadCameraCachePolicy.maximumPhotoCacheBytes)
    }

    func testCatalogLoadingAndMarkerSelectionNeverPrefetchPhotos() async {
        let upstream = CameraStub()
        let coordinator = RoadCameraMapCoordinator(repository: upstream)
        await coordinator.refresh(enabledTypes: [])
        XCTAssertEqual(upstream.catalogCalls, 0)
        await coordinator.refresh(enabledTypes: [.speed, .traffic])
        XCTAssertEqual(upstream.catalogCalls, 1)
        XCTAssertEqual(upstream.photoCalls, 0)
        coordinator.select(upstream.speed)
        await coordinator.loadPhoto(cameraId: upstream.speed.id)
        XCTAssertEqual(upstream.photoCalls, 0)
        coordinator.select(upstream.traffic)
        XCTAssertEqual(upstream.photoCalls, 0)
        await coordinator.loadPhoto(cameraId: upstream.traffic.id)
        XCTAssertEqual(upstream.photoCalls, 1)
        await coordinator.refresh(enabledTypes: [.speed])
        XCTAssertNil(coordinator.selectedCamera)
        XCTAssertNil(coordinator.photo)
    }

    func testCancelledDetailTaskDoesNotStrandLoadingForSameSelection() async {
        let upstream = CameraStub()
        upstream.suspendPhotos = true
        let coordinator = RoadCameraMapCoordinator(repository: upstream)
        await coordinator.refresh(enabledTypes: [.traffic])
        coordinator.select(upstream.traffic)
        let first = Task { await coordinator.loadPhoto(cameraId: upstream.traffic.id) }
        await waitForPendingPhotos(1, in: upstream)
        first.cancel()
        let restarted = Task { await coordinator.loadPhoto(cameraId: upstream.traffic.id) }
        await restarted.value
        upstream.completeFirstPhoto()
        await first.value
        XCTAssertFalse(coordinator.photoLoading)
        XCTAssertNotNil(coordinator.photo)
        XCTAssertEqual(upstream.photoCalls, 1)
    }

    func testSwitchingAwayAndBackRejectsOldSameCameraCompletion() async {
        let upstream = CameraStub()
        upstream.suspendPhotos = true
        let coordinator = RoadCameraMapCoordinator(repository: upstream)
        await coordinator.refresh(enabledTypes: [.speed, .traffic])
        coordinator.select(upstream.traffic)
        let first = Task { await coordinator.loadPhoto(cameraId: upstream.traffic.id) }
        await waitForPendingPhotos(1, in: upstream)
        coordinator.select(upstream.speed)
        coordinator.select(upstream.traffic)
        let second = Task { await coordinator.loadPhoto(cameraId: upstream.traffic.id) }
        await waitForPendingPhotos(2, in: upstream)
        upstream.completeFirstPhoto()
        await first.value
        XCTAssertTrue(coordinator.photoLoading)
        XCTAssertNil(coordinator.photo)
        upstream.completeFirstPhoto()
        await second.value
        XCTAssertFalse(coordinator.photoLoading)
        XCTAssertNotNil(coordinator.photo)
    }

    func testCorruptCachedCatalogIsReplacedRatherThanRendered() async throws {
        let location = directory()
        defer { try? FileManager.default.removeItem(at: location) }
        let upstream = CameraStub()
        let now = Date(timeIntervalSince1970: 10_000)
        let cache = CachedRoadCameraRepository(upstream: upstream, directory: location, now: { now })
        _ = try await cache.catalog()
        let url = location.appendingPathComponent("catalog.json")
        var entry = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        var catalog = entry["catalog"] as! [String: Any]
        var cameras = catalog["cameras"] as! [[String: Any]]
        cameras[0]["latitude"] = 999
        catalog["cameras"] = cameras
        entry["catalog"] = catalog
        try JSONSerialization.data(withJSONObject: entry).write(to: url)
        let restored = CachedRoadCameraRepository(upstream: upstream, directory: location, now: { now })
        let result = try await restored.catalog()
        XCTAssertTrue(result.isValid)
        XCTAssertEqual(upstream.catalogCalls, 2)
    }

    func testOversizedImageDimensionsAreRejectedBeforeBitmapDecode() {
        let oversized = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAEAAAAAABCAIAAADRQ/wrAAAAJElEQVR4nO3BMQEAAAwCoNk/9KzhAeQPAAAAAAAAAAAAAABYVzLSAQGKI+K0AAAAAElFTkSuQmCC")!
        XCTAssertLessThan(oversized.count, RoadCameraCachePolicy.maximumPhotoBytes)
        XCTAssertFalse(RoadCameraCachePolicy.isSafePhoto(RoadCameraPhoto(
            data: oversized, contentType: "image/png", photoTime: nil)))
        XCTAssertFalse(RoadCameraCachePolicy.isSafePhoto(RoadCameraPhoto(
            data: Data("invalid image".utf8), contentType: "image/png", photoTime: nil)))
    }

    private func waitForPendingPhotos(_ count: Int, in upstream: CameraStub) async {
        for _ in 0..<1_000 {
            if upstream.pendingPhotos.count >= count { return }
            await Task.yield()
        }
        XCTFail("image task did not start")
    }

    func testCameraMarkersUseDistinctLegibleGlyphs() {
        XCTAssertEqual(Set(RoadCameraType.allCases.map(\.symbol)).count, 3)
        for type in RoadCameraType.allCases {
            XCTAssertGreaterThanOrEqual(IncidentMarkerStyle.contrast(
                IncidentMarkerStyle.glyphColor(on: type.color), type.color), 4.5)
        }
        XCTAssertFalse(RoadCameraCachePolicy.isFresh(Date(timeIntervalSince1970: 101),
            at: Date(timeIntervalSince1970: 100), lifetime: 300))
    }
}

@MainActor
private final class CameraStub: RoadCameraRepository {
    var catalogCalls = 0
    var photoCalls = 0
    var requestedCameraIds: [String] = []
    var catalogCameras: [RoadCamera]?
    var failure: KccFunctionsError?
    var suspendPhotos = false
    var pendingPhotos: [CheckedContinuation<RoadCameraPhoto, Error>] = []
    let speed = RoadCamera(id: "speed", type: .speed, name: "Speed", latitude: 57.4, longitude: 12, direction: nil)
    let traffic = RoadCamera(id: "traffic", type: .traffic, name: "Traffic", latitude: 57.4, longitude: 12, direction: nil)

    func catalog() async throws -> RoadCameraCatalog {
        catalogCalls += 1
        await Task.yield()
        if let failure { throw failure }
        return RoadCameraCatalog(cameras: catalogCameras ?? [speed, traffic], updatedAt: nil)
    }

    func photo(cameraId: String) async throws -> RoadCameraPhoto {
        photoCalls += 1
        requestedCameraIds.append(cameraId)
        await Task.yield()
        if let failure { throw failure }
        if suspendPhotos {
            return try await withCheckedThrowingContinuation { pendingPhotos.append($0) }
        }
        return samplePhoto
    }

    func completeFirstPhoto() {
        guard !pendingPhotos.isEmpty else { return }
        pendingPhotos.removeFirst().resume(returning: samplePhoto)
    }

    private var samplePhoto: RoadCameraPhoto {
        let pixel = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNgKG0CAAFwAPgPVcyXAAAAAElFTkSuQmCC")!
        return RoadCameraPhoto(data: pixel, contentType: "image/png", photoTime: "2026-11-12T12:00:00Z")
    }
}
