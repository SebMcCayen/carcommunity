import Foundation

struct PendingDriveRouteUpload: Equatable, Sendable {
    let path: String
    let points: [RecordedDrivePoint]
}

/// Durable handoff between saving a ride summary and uploading its exact route.
/// Implementations must replace an entry atomically because the process may be
/// terminated immediately after `store` returns.
protocol DriveRouteUploadSpool: AnyObject {
    @discardableResult
    func store(path: String, points: [RecordedDrivePoint]) -> Bool
    func pendingUploads() -> [PendingDriveRouteUpload]
    func remove(path: String)
    func removeAll()
}

/// Owner-scoped, file-protected route upload spool. Each Storage path owns one
/// file so a failed/cancelled upload cannot affect any other pending route.
final class FileDriveRouteUploadSpool: DriveRouteUploadSpool {
    private let directoryURL: URL
    private let fileManager: FileManager

    init(directoryURL: URL, fileManager: FileManager = .default) {
        self.directoryURL = directoryURL
        self.fileManager = fileManager
    }

    @discardableResult
    func store(path: String, points: [RecordedDrivePoint]) -> Bool {
        guard Self.isValid(path: path), !points.isEmpty,
              points.count <= DriveRecorder.maximumRoutePoints,
              points.allSatisfy({ Self.isValid(point: $0) }) else { return false }
        do {
            try fileManager.createDirectory(
                at: directoryURL,
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
            )
            let payload = Payload(
                version: 1,
                path: path,
                points: points.map(Point.init)
            )
            let data = try JSONEncoder().encode(payload)
            try data.write(
                to: fileURL(for: path),
                options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
            )
            return true
        } catch {
            return false
        }
    }

    func pendingUploads() -> [PendingDriveRouteUpload] {
        guard let urls = try? fileManager.contentsOfDirectory(
            at: directoryURL,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else { return [] }
        return urls.sorted { $0.lastPathComponent < $1.lastPathComponent }.compactMap { url in
            guard url.pathExtension == "routeupload",
                  let data = try? Data(contentsOf: url),
                  let payload = try? JSONDecoder().decode(Payload.self, from: data),
                  payload.version == 1,
                  Self.isValid(path: payload.path),
                  payload.points.count <= DriveRecorder.maximumRoutePoints
            else { return nil }
            let points = payload.points.map(\.recorded)
            guard !points.isEmpty, points.allSatisfy({ Self.isValid(point: $0) }),
                  url == fileURL(for: payload.path) else { return nil }
            return PendingDriveRouteUpload(path: payload.path, points: points)
        }
    }

    func remove(path: String) {
        guard Self.isValid(path: path) else { return }
        try? fileManager.removeItem(at: fileURL(for: path))
        if (try? fileManager.contentsOfDirectory(atPath: directoryURL.path).isEmpty) == true {
            try? fileManager.removeItem(at: directoryURL)
        }
    }

    func removeAll() {
        try? fileManager.removeItem(at: directoryURL)
    }

    private func fileURL(for path: String) -> URL {
        let key = Data(path.utf8).base64EncodedString()
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "=", with: "")
        return directoryURL.appendingPathComponent("\(key).routeupload")
    }

    private static func isValid(path: String) -> Bool {
        path.hasPrefix("rideRoutes/") && path.hasSuffix("/route.bin") && path.utf8.count <= 1_024
    }

    private static func isValid(point: RecordedDrivePoint) -> Bool {
        point.latitude.isFinite && (-90...90).contains(point.latitude)
            && point.longitude.isFinite && (-180...180).contains(point.longitude)
    }

    private struct Payload: Codable {
        let version: Int
        let path: String
        let points: [Point]
    }

    private struct Point: Codable {
        let latitude: Double
        let longitude: Double
        let timestampMilliseconds: Int64

        init(_ point: RecordedDrivePoint) {
            latitude = point.latitude
            longitude = point.longitude
            timestampMilliseconds = point.timestampMilliseconds
        }

        var recorded: RecordedDrivePoint {
            RecordedDrivePoint(
                latitude: latitude,
                longitude: longitude,
                timestampMilliseconds: timestampMilliseconds
            )
        }
    }
}
