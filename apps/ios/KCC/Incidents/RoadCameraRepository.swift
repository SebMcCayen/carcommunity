import Foundation
import CryptoKit

@MainActor
protocol RoadCameraRepository: AnyObject {
    func catalog() async throws -> RoadCameraCatalog
    func photo(cameraId: String) async throws -> RoadCameraPhoto
}

@MainActor
final class FirebaseRoadCameraRepository: RoadCameraRepository {
    private let client: KccFunctionsClient

    private init(client: KccFunctionsClient) { self.client = client }

    static func createIfAvailable() -> FirebaseRoadCameraRepository? {
        KccFunctionsClient.createIfAvailable().map(FirebaseRoadCameraRepository.init(client:))
    }

    func catalog() async throws -> RoadCameraCatalog {
        try await Self.fetchCatalog(client: client)
    }

    nonisolated private static func fetchCatalog(client: KccFunctionsClient) async throws -> RoadCameraCatalog {
        guard let raw = try await client.call("roadCameras-catalog", payload: [:]),
              JSONSerialization.isValidJSONObject(raw) else {
            throw KccFunctionsError(code: .internalError)
        }
        let data = try JSONSerialization.data(withJSONObject: raw)
        guard data.count <= 4 * 1024 * 1024 else { throw KccFunctionsError(code: .internalError) }
        let catalog = try JSONDecoder().decode(RoadCameraCatalog.self, from: data)
        guard catalog.isValid else {
            throw KccFunctionsError(code: .internalError)
        }
        return catalog
    }

    func photo(cameraId: String) async throws -> RoadCameraPhoto {
        try await Self.fetchPhoto(cameraId: cameraId, client: client)
    }

    nonisolated private static func fetchPhoto(cameraId: String, client: KccFunctionsClient) async throws -> RoadCameraPhoto {
        guard let raw = try await client.call("roadCameras-photo", payload: ["cameraId": cameraId]) as? [String: Any],
              let encoded = raw["base64"] as? String,
              encoded.utf8.count <= 180_000,
              let contentType = raw["contentType"] as? String,
              ["image/jpeg", "image/png"].contains(contentType),
              let data = Data(base64Encoded: encoded), !data.isEmpty,
              data.count <= RoadCameraCachePolicy.maximumPhotoBytes else {
            throw KccFunctionsError(code: .internalError)
        }
        let photo = RoadCameraPhoto(data: data, contentType: contentType,
                                   photoTime: raw["photoTime"] as? String)
        guard RoadCameraCachePolicy.isSafePhoto(photo) else { throw KccFunctionsError(code: .internalError) }
        return photo
    }
}

/// Public road metadata and photographs only. No credentials or user data enter disk cache.
/// Both in-flight coalescing and throttling precede the remote image callable.
@MainActor
final class CachedRoadCameraRepository: RoadCameraRepository {
    private struct CatalogEntry: Codable { let fetchedAt: Date; let catalog: RoadCameraCatalog }
    private struct PhotoEntry: Codable { let cameraId: String; let fetchedAt: Date; let photo: RoadCameraPhoto }
    private let upstream: any RoadCameraRepository
    private let directory: URL
    private let now: () -> Date
    private var catalogEntry: CatalogEntry?
    private var photoEntries: [String: PhotoEntry] = [:]
    private var photoAccess: [String: Date] = [:]
    private var catalogTask: Task<RoadCameraCatalog, Error>?
    private var photoTasks: [String: Task<RoadCameraPhoto, Error>] = [:]
    private var requestDates: [Date] = []
    private var photoRetryAt = Date.distantPast
    private var catalogRetryAt = Date.distantPast

    init(upstream: any RoadCameraRepository, directory: URL? = nil, now: @escaping () -> Date = Date.init) {
        self.upstream = upstream
        self.now = now
        self.directory = directory ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("road-cameras-v1", isDirectory: true)
        try? FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
        trimDisk()
    }

    func catalog() async throws -> RoadCameraCatalog {
        if catalogEntry == nil,
           let saved = read(CatalogEntry.self, at: directory.appendingPathComponent("catalog.json"), maximumBytes: 4 * 1024 * 1024),
           saved.catalog.isValid, saved.fetchedAt <= now() {
            catalogEntry = saved
        }
        if let entry = catalogEntry,
           RoadCameraCachePolicy.isFresh(entry.fetchedAt, at: now(), lifetime: RoadCameraCachePolicy.catalogLifetime) {
            return entry.catalog
        }
        if let catalogTask { return try await catalogTask.value }
        if now() < catalogRetryAt {
            if let catalogEntry { return catalogEntry.catalog }
            throw KccFunctionsError(code: .unavailable)
        }
        let task = Task { try await upstream.catalog() }
        catalogTask = task
        defer { catalogTask = nil }
        do {
            let catalog = try await task.value
            guard catalog.isValid else { throw KccFunctionsError(code: .internalError) }
            let entry = CatalogEntry(fetchedAt: now(), catalog: catalog)
            catalogEntry = entry
            write(entry, to: directory.appendingPathComponent("catalog.json"))
            return catalog
        } catch {
            catalogRetryAt = now().addingTimeInterval(60)
            if let catalogEntry { return catalogEntry.catalog }
            throw error
        }
    }

    func photo(cameraId: String) async throws -> RoadCameraPhoto {
        let date = now()
        let url = photoURL(cameraId)
        if photoEntries[cameraId] == nil,
           let entry = read(PhotoEntry.self, at: url, maximumBytes: 256 * 1024), entry.cameraId == cameraId,
           RoadCameraCachePolicy.isSafePhoto(entry.photo) {
            photoEntries[cameraId] = entry
        }
        if let entry = photoEntries[cameraId],
           RoadCameraCachePolicy.isFresh(entry.fetchedAt, at: date, lifetime: RoadCameraCachePolicy.photoLifetime) {
            photoAccess[cameraId] = date
            try? FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: url.path)
            trimMemory()
            return entry.photo
        }
        if let task = photoTasks[cameraId] { return try await task.value }
        requestDates = requestDates.filter { date.timeIntervalSince($0) >= 0 && date.timeIntervalSince($0) < 60 }
        guard date >= photoRetryAt, requestDates.count < RoadCameraCachePolicy.requestsPerMinute else {
            throw KccFunctionsError(code: .resourceExhausted)
        }
        requestDates.append(date)
        let task = Task { try await upstream.photo(cameraId: cameraId) }
        photoTasks[cameraId] = task
        defer { photoTasks[cameraId] = nil }
        do {
            let photo = try await task.value
            guard RoadCameraCachePolicy.isSafePhoto(photo) else { throw KccFunctionsError(code: .internalError) }
            let entry = PhotoEntry(cameraId: cameraId, fetchedAt: now(), photo: photo)
            photoEntries[cameraId] = entry
            photoAccess[cameraId] = now()
            write(entry, to: url)
            trimMemory()
            trimDisk()
            return photo
        } catch {
            let failure = error as? KccFunctionsError
            let delay: TimeInterval = failure?.code == .resourceExhausted
                ? max(60, Double(failure?.retryAfterMilliseconds ?? 60_000) / 1000) : 30
            photoRetryAt = now().addingTimeInterval(min(delay, 86_400))
            throw error
        }
    }

    private func photoURL(_ id: String) -> URL {
        let name = SHA256.hash(data: Data(id.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent("photo-" + name + ".json")
    }

    private func trimMemory() {
        while photoEntries.count > RoadCameraCachePolicy.maximumPhotos
            || photoEntries.values.reduce(0, { $0 + $1.photo.data.count }) > RoadCameraCachePolicy.maximumPhotoCacheBytes {
            guard let oldest = photoEntries.keys.min(by: { (photoAccess[$0] ?? .distantPast) < (photoAccess[$1] ?? .distantPast) }) else { return }
            photoEntries[oldest] = nil
            photoAccess[oldest] = nil
        }
    }

    private func trimDisk() {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey])) ?? []
        var photos = files.filter { $0.lastPathComponent.hasPrefix("photo-") }.map { url in
            let values = try? url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
            return (url: url, date: values?.contentModificationDate ?? .distantPast, bytes: values?.fileSize ?? 0)
        }.sorted { $0.date < $1.date }
        var bytes = photos.reduce(0) { $0 + $1.bytes }
        while photos.count > RoadCameraCachePolicy.maximumPhotos || bytes > RoadCameraCachePolicy.maximumPhotoCacheBytes {
            let oldest = photos.removeFirst()
            bytes -= oldest.bytes
            try? FileManager.default.removeItem(at: oldest.url)
        }
    }

    private func read<T: Decodable>(_ type: T.Type, at url: URL, maximumBytes: Int) -> T? {
        guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size <= maximumBytes, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private func write<T: Encodable>(_ value: T, to url: URL) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }
}
