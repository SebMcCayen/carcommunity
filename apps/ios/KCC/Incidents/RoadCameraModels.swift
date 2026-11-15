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

    /// The speed camera uses the same vector artwork as Android.
    var assetName: String? { self == .speed ? "camera-speed" : nil }

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
                          glyphColorArgb: IncidentMarkerStyle.glyphColor(on: type.color),
                          assetName: type.assetName)
    }
}

/// A named photo-camera site or a verified pair of opposite-facing speed cameras.
struct RoadCameraLocation: Equatable, Sendable {
    let views: [RoadCamera]
    var camera: RoadCamera { views[0] }

    static func group(_ cameras: [RoadCamera]) -> [RoadCameraLocation] {
        let named = Dictionary(grouping: cameras) {
            $0.type.rawValue + "|" + $0.name.trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased(with: Locale(identifier: "en_US_POSIX"))
        }
        var locations: [RoadCameraLocation] = []
        for cameras in named.values {
            var groups: [[RoadCamera]] = []
            // Stable anchors avoid catalog-order differences and proximity chains.
            for camera in cameras.sorted(by: { $0.id < $1.id }) {
                let index = groups.firstIndex {
                    let anchor = $0[0]
                    let north = (camera.latitude - anchor.latitude) * 111_000
                    let east = (camera.longitude - anchor.longitude) * 111_000 * cos(anchor.latitude * .pi / 180)
                    if camera.type == .speed {
                        return $0.count == 1 && hypot(north, east) <= 150 && anchor.opposes(camera)
                    }
                    return hypot(north, east) <= 50
                }
                if let index { groups[index].append(camera) }
                else { groups.append([camera]) }
            }
            locations += groups.map { group in
                RoadCameraLocation(views: group.sorted {
                    let lhs = $0.validDirection ?? 361
                    let rhs = $1.validDirection ?? 361
                    return lhs == rhs ? $0.id < $1.id : lhs < rhs
                })
            }
        }
        return locations.sorted { $0.camera.id < $1.camera.id }
    }
}

struct RoadCameraMapGroup: Equatable, Identifiable, Sendable {
    let sites: [RoadCameraLocation]
    // Keep the marker at a real camera rather than a centroid across a road or lawn.
    var camera: RoadCamera { sites[0].camera }
    var id: String { camera.id }
    var views: [RoadCamera] { sites.flatMap(\.views) }
    var opposingDirections: Bool {
        sites.count == 1 && camera.type == .speed && sites[0].views.count == 2
            && sites[0].views[0].opposes(sites[0].views[1])
    }

    static func group(_ sites: [RoadCameraLocation], zoom: Double) -> [RoadCameraMapGroup] {
        let bucket = max(0, min(22, floor(zoom.isFinite ? zoom : 14)))
        let ordered = sites.sorted { $0.camera.id < $1.camera.id }
        if bucket >= 17 { return ordered.map { RoadCameraMapGroup(sites: [$0]) } }
        guard let latitude = ordered.first?.camera.latitude else { return [] }
        let longitudeScale = max(0.01, cos(latitude * .pi / 180))
        let radius = max(1, min(10_000, 64 * 78_271.516964 * longitudeScale / pow(2, bucket)))
        struct Cell: Hashable { let type: RoadCameraType; let x: Int; let y: Int }
        var cells: [Cell: [Int]] = [:]
        var groups: [[RoadCameraLocation]] = []
        for site in ordered {
            let camera = site.camera
            let x = Int(floor(camera.longitude * 111_000 * longitudeScale / radius))
            let y = Int(floor(camera.latitude * 111_000 / radius))
            var candidates: [Int] = []
            for dx in -1...1 { for dy in -1...1 {
                candidates += cells[Cell(type: camera.type, x: x + dx, y: y + dy)] ?? []
            } }
            let index = candidates.filter { candidate in
                let anchor = groups[candidate][0].camera
                let north = (camera.latitude - anchor.latitude) * 111_000
                let east = (camera.longitude - anchor.longitude) * 111_000 * longitudeScale
                return hypot(north, east) <= radius
            }.min()
            if let index { groups[index].append(site) }
            else {
                cells[Cell(type: camera.type, x: x, y: y), default: []].append(groups.count)
                groups.append([site])
            }
        }
        return groups.map { RoadCameraMapGroup(sites: $0) }
    }
}

extension RoadCamera {
    func opposes(_ other: RoadCamera) -> Bool {
        guard let a = validDirection, let b = other.validDirection else { return false }
        let difference = abs(a - b).truncatingRemainder(dividingBy: 360)
        return min(difference, 360 - difference) >= 135
    }
    var validDirection: Double? {
        guard let direction, direction.isFinite, (0...360).contains(direction) else { return nil }
        return direction.truncatingRemainder(dividingBy: 360)
    }

    var compassIndex: Int? {
        validDirection.map { Int(($0 + 22.5) / 45) % 8 }
    }

    func viewLabel(in views: [RoadCamera]) -> String {
        let number = (views.firstIndex(where: { $0.id == id }) ?? 0) + 1
        guard let compassIndex else {
            return String(format: NSLocalizedString("roadCameras.numberedView", comment: ""), number)
        }
        let key = ["north", "northEast", "east", "southEast", "south", "southWest", "west", "northWest"][compassIndex]
        let label = NSLocalizedString("roadCameras." + key, comment: "")
        return views.filter { $0.compassIndex == compassIndex }.count > 1
            ? String(format: NSLocalizedString("roadCameras.numberedDirection", comment: ""), label, number) : label
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
