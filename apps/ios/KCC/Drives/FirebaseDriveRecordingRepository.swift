import FirebaseCore
import FirebaseStorage
import Foundation

final class FirebaseDriveRecordingRepository: DriveRecordingRepository, @unchecked Sendable {
    private let functions: KccFunctionsClient
    private let storage: Storage

    private init(functions: KccFunctionsClient, storage: Storage) {
        self.functions = functions
        self.storage = storage
    }

    func save(_ request: DriveSaveRequest) async throws -> DriveSaveResult {
        let raw = try await functions.call("drives-save", payload: request.payload)
        guard let map = raw as? [String: Any],
              let rideId = (map["rideId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !rideId.isEmpty
        else { throw DriveRecordingRepositoryError.malformedResponse }
        let routePath = (map["routePath"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return DriveSaveResult(
            rideId: rideId,
            routePath: routePath?.isEmpty == false ? routePath : nil,
            alreadySaved: map["alreadySaved"] as? Bool ?? false
        )
    }

    func uploadRoute(_ points: [RecordedDrivePoint], to path: String) async throws {
        guard !points.isEmpty else { return }
        guard path.hasPrefix("rideRoutes/"), path.hasSuffix("/route.bin") else {
            throw DriveRecordingRepositoryError.malformedResponse
        }
        let metadata = StorageMetadata()
        metadata.contentType = "application/octet-stream"
        let data = DriveRouteCodec.encode(points)
        var lastError: Error?
        for attempt in 0..<3 {
            do {
                _ = try await storage.reference(withPath: path).putDataAsync(
                    data,
                    metadata: metadata
                )
                return
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                lastError = error
                if attempt < 2 {
                    try await Task.sleep(for: .milliseconds(500 * (1 << attempt)))
                }
            }
        }
        throw lastError ?? DriveRecordingRepositoryError.malformedResponse
    }

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseDriveRecordingRepository?

    static func createIfAvailable() -> DriveRecordingRepository? {
        guard FirebaseApp.app() != nil, let functions = KccFunctionsClient.createIfAvailable()
        else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let storage = Storage.storage()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_STORAGE_EMULATOR_HOST"]
        ) {
            storage.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseDriveRecordingRepository(functions: functions, storage: storage)
        cached = repository
        return repository
    }
}
