import Foundation
import ImageIO

enum RoadCameraType: String, Codable, CaseIterable, Hashable, Sendable {
    case speed
    case traffic
    case roadCondition = "road_condition"

    var titleKey: String {
        switch self {
        case .speed: "roadCameras.speedLayer"
        case .traffic: "roadCameras.trafficLayer"
        case .roadCondition: "roadCameras.roadConditionLayer"
        }
    }

    var symbol: String {
        switch self {
        case .speed: "speedometer"
        case .traffic: "video.fill"
        case .roadCondition: "cloud.snow.fill"
        }
    }

    var color: UInt32 {
        switch self {
        case .speed: 0xFFEAB54B
        case .traffic: 0xFF007582
        case .roadCondition: 0xFF526172
        }
    }
}

struct RoadCamera: Codable, Identifiable, Equatable, Sendable {
    let id: String
    let type: RoadCameraType
    let name: String
    let latitude: Double
    let longitude: Double
    let direction: Double?

    var isValid: Bool {
        !id.isEmpty && id.utf8.count <= 256
            && !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && name.utf8.count <= 2_000
            && latitude.isFinite && longitude.isFinite
            && (-90...90).contains(latitude) && (-180...180).contains(longitude)
            && direction?.isFinite != false
    }

    var marker: MapIncidentMarker {
        MapIncidentMarker(id: id, longitude: longitude, latitude: latitude,
                          colorArgb: type.color, iconName: type.symbol,
                          glyphColorArgb: IncidentMarkerStyle.glyphColor(on: type.color))
    }
}

struct RoadCameraCatalog: Codable, Sendable {
    let cameras: [RoadCamera]
    let updatedAt: String?

    var isValid: Bool {
        cameras.count <= 10_000 && cameras.allSatisfy(\.isValid)
            && Set(cameras.map(\.id)).count == cameras.count
    }
}

struct RoadCameraPhoto: Codable, Sendable, Equatable {
    let data: Data
    let contentType: String
    let photoTime: String?
}

/// Cache dates reject future values as well as expired entries after clock changes.
enum RoadCameraCachePolicy {
    static let catalogLifetime: TimeInterval = 24 * 60 * 60
    static let photoLifetime: TimeInterval = 5 * 60
    static let maximumPhotoBytes = 128 * 1024
    static let maximumPhotos = 20
    static let maximumPhotoCacheBytes = 4 * 1024 * 1024
    static let requestsPerMinute = 6
    static let maximumImageDimension = 2048

    /// Read image metadata without allocating an untrusted decoded bitmap.
    static func isSafePhoto(_ photo: RoadCameraPhoto) -> Bool {
        guard ["image/jpeg", "image/png"].contains(photo.contentType),
              !photo.data.isEmpty, photo.data.count <= maximumPhotoBytes,
              let source = CGImageSourceCreateWithData(photo.data as CFData,
                  [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) == 1,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
              let height = properties[kCGImagePropertyPixelHeight] as? NSNumber else { return false }
        return (1...maximumImageDimension).contains(width.intValue)
            && (1...maximumImageDimension).contains(height.intValue)
    }

    static func isFresh(_ date: Date, at now: Date, lifetime: TimeInterval) -> Bool {
        let age = now.timeIntervalSince(date)
        return age >= 0 && age < lifetime
    }
}
