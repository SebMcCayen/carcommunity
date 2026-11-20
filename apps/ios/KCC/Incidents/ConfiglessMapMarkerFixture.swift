#if DEBUG
import SwiftUI
import FirebaseCore

/// Real marker badges directly underneath the shell controls, without network,
/// a renderer token or a production account. Absent from shipping builds.
struct ConfiglessMapMarkerFixture: View {
    static var isEnabled: Bool {
        FirebaseApp.app() == nil && ProcessInfo.processInfo.arguments.contains("-KCCUITestMapMarkers")
    }
    let mapMode: MapMode
    @State private var selected = false

    private var points: [CGPoint] {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-KCCUITestMapMarkers"), i + 1 < args.count else { return [] }
        return args[i + 1].split(separator: ";").compactMap { pair in
            let xy = pair.split(separator: ",").compactMap { Double($0) }
            guard xy.count == 2, xy.allSatisfy({ $0.isFinite }) else { return nil }
            return CGPoint(x: xy[0], y: xy[1])
        }
    }

    var body: some View {
        GeometryReader { geometry in
            let origin = geometry.frame(in: .global).origin
            ZStack {
                let centers = points
                let bounds = centers.reduce(CGRect.null) { result, point in
                    result.union(CGRect(x: point.x - 24, y: point.y - 24, width: 48, height: 48))
                }
                if !bounds.isNull {
                    // Bound XCTest's physical pinch to the occupied map area.
                    Color.clear
                        .frame(width: bounds.width, height: bounds.height)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("Traffic marker field")
                        .accessibilityIdentifier("map.fixture.markerField")
                        .position(x: bounds.midX - origin.x, y: bounds.midY - origin.y)
                }
                ForEach(Array(centers.enumerated()), id: \.offset) { index, point in
                    MapTapMarker(center: CGPoint(x: point.x - origin.x, y: point.y - origin.y),
                                 interactive: true, accessibilityLabel: "Test roadwork marker",
                                 identifier: "map.fixture.covered.\(index)", select: { selected = true }) {
                        IncidentMarkerBadge(marker: marker(.roadwork, id: "covered-\(index)"))
                    }
                }
                ForEach(Array(IncidentType.allCases.enumerated()), id: \.offset) { index, type in
                    VStack(spacing: 12) {
                        IncidentMarkerBadge(marker: marker(type, id: type.rawValue))
                        IncidentMarkerBadge(marker: marker(type, id: type.rawValue + "-cleared", cleared: true))
                    }
                    .position(x: 35 + CGFloat(index) * 48, y: geometry.size.height * 0.45)
                }
                ForEach(0..<5, id: \.self) { index in
                    VStack(spacing: 12) {
                        CrownMarkerTile(rank: index == 4 ? 2 : index, inRange: false, mapMode: mapMode, isCuratedPoint: index == 4)
                        CrownMarkerTile(rank: index == 4 ? 2 : index, inRange: true, mapMode: mapMode, isCuratedPoint: index == 4)
                    }
                    .position(x: 35 + CGFloat(index) * 48, y: geometry.size.height * 0.30)
                }
                Text(mapMode.rawValue).font(.caption)
                    .accessibilityIdentifier("map.fixture.crown-mode")
                    .position(x: geometry.size.width * 0.5, y: geometry.size.height * 0.36)
                if selected {
                    Text("Covered marker selected").accessibilityIdentifier("map.fixture.wrongTarget")
                }
            }
            .allowsHitTesting(false)
        }
    }

    private func marker(_ type: IncidentType, id: String, cleared: Bool = false) -> MapIncidentMarker {
        MapIncidentMarker(id: id, longitude: 12, latitude: 57,
            colorArgb: type.markerColorArgb, iconName: type.symbolName,
            glyphColorArgb: type.glyphColorArgb, reportedCleared: cleared, assetName: type.markerAssetName)
    }
}
#endif
