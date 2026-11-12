import XCTest
@testable import KCC

@MainActor
final class RoadCameraTests: XCTestCase {
    private func directory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("RoadCameraTests-" + UUID().uuidString)
    }

    func testLayersStartOffAndEveryChoicePersistsIndependently() {
        let suite = "RoadCameraTests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = MapLayerPreferences(defaults: defaults)
        XCTAssertTrue(preferences.cameraLayers.isEmpty)
        preferences.setCameraLayer(.speed, enabled: true)
        preferences.setCameraLayer(.traffic, enabled: true)
        preferences.setCameraLayer(.roadCondition, enabled: true)
        preferences.setCameraLayer(.traffic, enabled: false)
        XCTAssertEqual(MapLayerPreferences(defaults: defaults).cameraLayers, [.speed, .roadCondition])
        XCTAssertFalse(MapLayerPreferences(defaults: defaults).trafficEnabled)
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
    var failure: KccFunctionsError?
    var suspendPhotos = false
    var pendingPhotos: [CheckedContinuation<RoadCameraPhoto, Error>] = []
    let speed = RoadCamera(id: "speed", type: .speed, name: "Speed", latitude: 57.4, longitude: 12, direction: nil)
    let traffic = RoadCamera(id: "traffic", type: .traffic, name: "Traffic", latitude: 57.4, longitude: 12, direction: nil)

    func catalog() async throws -> RoadCameraCatalog {
        catalogCalls += 1
        await Task.yield()
        if let failure { throw failure }
        return RoadCameraCatalog(cameras: [speed, traffic], updatedAt: nil)
    }

    func photo(cameraId: String) async throws -> RoadCameraPhoto {
        photoCalls += 1
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
