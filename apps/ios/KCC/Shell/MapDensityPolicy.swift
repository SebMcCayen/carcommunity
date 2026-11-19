import Foundation

struct MapDensityScreenPoint: Equatable { let x: Double; let y: Double }

struct MapDensityItem: Equatable, Identifiable {
    let id: String
    let latitude: Double
    let longitude: Double
    var count = 1
    var critical = false
    var selected = false
}
struct MapDensityGroup: Identifiable, Equatable {
    let members: [MapDensityItem]
    var anchor: MapDensityItem { members.first(where: \.selected) ?? members[0] }
    var id: String { members.map(\.id).min()! }
    var count: Int { members.reduce(0) { $0 + max(1, $1.count) } }
    var critical: Bool { members.contains(where: \.critical) }
    var selected: Bool { members.contains(where: \.selected) }
    var compactCount: String { count > 99 ? "99+" : String(count) }
}
struct MapDensityZoom {
    private var bucket: Int?
    mutating func update(_ zoom: Double) -> Int {
        let z = zoom.isFinite ? max(0, min(22, zoom)) : 14
        var value = bucket ?? Int(floor(z))
        while value < 22 && z >= Double(value) + 1.15 { value += 1 }
        while value > 0 && z < Double(value) - 0.15 { value -= 1 }
        bucket = value
        return value
    }
}
/// Android parity: fixed world-pixel radius, real anchors, no source ids discarded.
enum MapDensityPolicy {
    static let radiusPixels = 56.0
    static func badgePosition(anchor: MapDensityScreenPoint, own: MapDensityScreenPoint?, width: Double, height: Double) -> MapDensityScreenPoint {
        guard let own, hypot(anchor.x - own.x, anchor.y - own.y) < 44 else { return anchor }
        let candidates = (0...7).map { index in
            let angle = Double(-45 + index * 45) * .pi / 180
            return MapDensityScreenPoint(x: own.x + cos(angle) * 44, y: own.y + sin(angle) * 44)
        }.filter { $0.x >= 24 && $0.x <= width - 24 && $0.y >= 24 && $0.y <= height - 24 && hypot($0.x - anchor.x, $0.y - anchor.y) <= 88 }
        return candidates.min { hypot($0.x - anchor.x, $0.y - anchor.y) < hypot($1.x - anchor.x, $1.y - anchor.y) } ?? anchor
    }

    static func groups(_ items: [MapDensityItem], zoomBucket: Int) -> [MapDensityGroup] {
        let size = 512 * pow(2, Double(max(0, min(22, zoomBucket))))
        struct Cell: Hashable { let x: Int; let y: Int }
        var groups: [[MapDensityItem]] = []
        var anchors: [(Double, Double)] = []
        var cells: [Cell: [Int]] = [:]
        var seen: Set<String> = []
        let ordered = items.filter { $0.latitude.isFinite && $0.longitude.isFinite && (-90...90).contains($0.latitude) && (-180...180).contains($0.longitude) && seen.insert($0.id).inserted }.sorted {
            if $0.critical != $1.critical { return $0.critical }
            return $0.id < $1.id
        }
        for item in ordered {
            let latitude = max(-85.05112878, min(85.05112878, item.latitude)) * .pi / 180
            let x = (item.longitude + 180) / 360 * size
            let y = (1 - log(tan(latitude) + 1 / cos(latitude)) / .pi) / 2 * size
            let cell = Cell(x: Int(floor(x / radiusPixels)), y: Int(floor(y / radiusPixels)))
            var candidates: [Int] = []
            for dx in -1...1 { for dy in -1...1 {
                candidates += cells[Cell(x: cell.x + dx, y: cell.y + dy)] ?? []
            } }
            let match = candidates.filter { hypot(x - anchors[$0].0, y - anchors[$0].1) <= radiusPixels }.min()
            if let match { groups[match].append(item) }
            else {
                cells[cell, default: []].append(groups.count)
                anchors.append((x, y))
                groups.append([item])
            }
        }
        return groups.map { MapDensityGroup(members: $0) }
    }
}
