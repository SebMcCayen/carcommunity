import FirebaseCore
import FirebaseStorage
import Foundation
import zlib

final class FirebaseDriveHistoryRepository: DriveHistoryRepository, @unchecked Sendable {
    private let functions: KccFunctionsClient
    private let storage: Storage
    private let session: URLSession
    private let cache = RouteCache()

    private init(functions: KccFunctionsClient, storage: Storage, session: URLSession = .shared) {
        self.functions = functions
        self.storage = storage
        self.session = session
    }

    func listHistory(cursorRideId: String?, pageSize: Int) async throws -> DriveHistoryPage {
        var payload: [String: Any] = ["pageSize": min(25, max(1, pageSize))]
        if let cursorRideId, !cursorRideId.isEmpty { payload["cursorRideId"] = cursorRideId }
        do {
            return try DriveHistoryPage.fromWire(
                try await functions.call("drives-listHistory", payload: payload)
            )
        } catch let error as KccFunctionsError {
            throw DriveHistoryError.callable(error.code)
        }
    }

    func fetchStats(monthStart: Date, monthEnd: Date) async throws -> DriveStatsSnapshot {
        let payload: [String: Any] = [
            "monthStartMillis": Int64((monthStart.timeIntervalSince1970 * 1_000).rounded()),
            "monthEndMillis": Int64((monthEnd.timeIntervalSince1970 * 1_000).rounded()),
        ]
        do {
            return try DriveStatsSnapshot.fromWire(
                try await functions.call("drives-stats", payload: payload)
            )
        } catch let error as KccFunctionsError {
            throw DriveHistoryError.callable(error.code)
        }
    }

    func deleteDrive(rideId: String) async throws {
        do {
            _ = try await functions.call("drives-delete", payload: ["rideId": rideId])
            await cache.remove(rideId)
        } catch let error as KccFunctionsError {
            throw DriveHistoryError.callable(error.code)
        }
    }

    func loadRoute(rideId: String) async throws -> DriveRouteReplayState {
        if let points = await cache.value(rideId) { return .ready(points) }
        do {
            guard let response = try await functions.call(
                "drives-routeUrl", payload: ["rideId": rideId]
            ) as? [String: Any],
            let rawURL = response["url"] as? String,
            let url = URL(string: rawURL), url.scheme == "https"
            else { return .unavailable }
            let (temporaryURL, urlResponse) = try await session.download(from: url)
            guard (urlResponse as? HTTPURLResponse)?.statusCode == 200 else { return .unavailable }
            let attributes = try FileManager.default.attributesOfItem(atPath: temporaryURL.path)
            guard let size = attributes[.size] as? NSNumber,
                  size.int64Value <= 16 * 1_024 * 1_024 else { return .unavailable }
            let data = try Data(contentsOf: temporaryURL, options: .mappedIfSafe)
            guard let points = DriveRouteCodec.decode(data) else { return .unavailable }
            await cache.insert(points, for: rideId)
            return .ready(points)
        } catch is CancellationError {
            // A disappearing/replaced detail task is not evidence that the
            // stored route is unavailable. Preserve cancellation for the
            // coordinator's identity fence.
            throw CancellationError()
        } catch {
            return .unavailable
        }
    }

    func imageDownloadURL(for imagePath: String) async -> URL? {
        try? await storage.reference(withPath: imagePath).downloadURL()
    }

    static func createIfAvailable() -> DriveHistoryRepository? {
        guard FirebaseApp.app() != nil, let functions = KccFunctionsClient.createIfAvailable()
        else { return nil }
        let storage = Storage.storage()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_STORAGE_EMULATOR_HOST"]
        ) {
            storage.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        return FirebaseDriveHistoryRepository(functions: functions, storage: storage)
    }
}

private actor RouteCache {
    private var values: [String: [DriveRoutePoint]] = [:]
    func value(_ rideId: String) -> [DriveRoutePoint]? { values[rideId] }
    func insert(_ points: [DriveRoutePoint], for rideId: String) { values[rideId] = points }
    func remove(_ rideId: String) { values[rideId] = nil }
}

extension DriveRouteCodec {
    static func decode(_ input: Data?) -> [DriveRoutePoint]? {
        guard let input, !input.isEmpty else { return nil }
        let data: Data
        if input.starts(with: [0x1f, 0x8b]) {
            guard input.count <= 16 * 1_024 * 1_024,
                  let inflated = gunzip(input, maximumOutputBytes: 24 * 1_024 * 1_024)
            else { return nil }
            data = inflated
        } else {
            data = input
        }
        guard data.count >= 6,
              Array(data.prefix(5)) == [0x43, 0x43, 0x52, 0x42, 0x01]
        else { return nil }
        var cursor = 6
        guard let countRaw = readUnsigned(data, cursor: &cursor), countRaw <= 1_000_000 else {
            return nil
        }
        var latitude = Int64(0)
        var longitude = Int64(0)
        var timestamp = UInt64(0)
        var points: [DriveRoutePoint] = []
        points.reserveCapacity(min(Int(countRaw), 20_000))
        for _ in 0..<Int(countRaw) {
            guard let latitudeDelta = readUnsigned(data, cursor: &cursor),
                  let longitudeDelta = readUnsigned(data, cursor: &cursor),
                  let timestampDelta = readUnsigned(data, cursor: &cursor),
                  latitudeDelta <= UInt64(UInt32.max),
                  longitudeDelta <= UInt64(UInt32.max),
                  timestampDelta <= UInt64(Int64.max) - timestamp
            else { return nil }
            latitude += unZigZag(latitudeDelta)
            longitude += unZigZag(longitudeDelta)
            timestamp += timestampDelta
            guard abs(latitude) <= 9_000_000, abs(longitude) <= 18_000_000 else { return nil }
            points.append(DriveRoutePoint(
                latitude: Double(latitude) / 100_000,
                longitude: Double(longitude) / 100_000,
                timestampMilliseconds: Int64(timestamp)
            ))
        }
        return points
    }

    private static func readUnsigned(_ data: Data, cursor: inout Int) -> UInt64? {
        var result: UInt64 = 0
        var shift = 0
        while shift < 64 {
            guard cursor < data.count else { return nil }
            let byte = data[cursor]
            cursor += 1
            if shift == 63, byte & 0x7e != 0 { return nil }
            result |= UInt64(byte & 0x7f) << shift
            if byte & 0x80 == 0 { return result }
            shift += 7
        }
        return nil
    }

    private static func unZigZag(_ value: UInt64) -> Int64 {
        Int64(value >> 1) ^ -Int64(value & 1)
    }

    /// `NSData.CompressionAlgorithm.zlib` rejects the gzip envelope used by
    /// Android's historical route uploads. zlib's `15 + 16` window mode reads
    /// that envelope while the explicit cap prevents a compressed route from
    /// expanding without bound.
    private static func gunzip(_ data: Data, maximumOutputBytes: Int) -> Data? {
        var stream = z_stream()
        guard inflateInit2_(
            &stream, MAX_WBITS + 16, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)
        ) == Z_OK else { return nil }
        defer { inflateEnd(&stream) }

        return data.withUnsafeBytes { inputBytes -> Data? in
            guard let input = inputBytes.bindMemory(to: Bytef.self).baseAddress else { return nil }
            stream.next_in = UnsafeMutablePointer(mutating: input)
            stream.avail_in = uInt(inputBytes.count)
            var result = Data()
            var buffer = [UInt8](repeating: 0, count: 64 * 1_024)

            while true {
                let status: Int32 = buffer.withUnsafeMutableBytes { outputBytes in
                    stream.next_out = outputBytes.bindMemory(to: Bytef.self).baseAddress
                    stream.avail_out = uInt(outputBytes.count)
                    return inflate(&stream, Z_NO_FLUSH)
                }
                guard status == Z_OK || status == Z_STREAM_END else { return nil }
                let produced = buffer.count - Int(stream.avail_out)
                guard result.count + produced <= maximumOutputBytes else { return nil }
                result.append(contentsOf: buffer.prefix(produced))
                if status == Z_STREAM_END { return result }
                guard produced > 0 || stream.avail_in > 0 else { return nil }
            }
        }
    }
}
