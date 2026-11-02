#if DEBUG
import SwiftUI
import FirebaseCore

/// Real marker badges directly underneath the shell controls, without network,
/// a renderer token or a production account. Absent from shipping builds.
struct ConfiglessMapMarkerFixture: View {
    static var isEnabled: Bool {
        FirebaseApp.app() == nil && ProcessInfo.processInfo.arguments.contains("-KCCUITestMapMarkers")
    }
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
                ForEach(Array(points.enumerated()), id: \.offset) { index, point in
                    Button { selected = true } label: {
                        IncidentMarkerBadge(marker: marker(.roadwork, id: "covered-\(index)"))
                            .frame(width: 44, height: 44).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Test roadwork marker")
                    .accessibilityIdentifier("map.fixture.covered.\(index)")
                    .position(x: point.x - origin.x, y: point.y - origin.y)
                }
                ForEach(Array(IncidentType.allCases.enumerated()), id: \.offset) { index, type in
                    VStack(spacing: 12) {
                        IncidentMarkerBadge(marker: marker(type, id: type.rawValue))
                        IncidentMarkerBadge(marker: marker(type, id: type.rawValue + "-cleared", cleared: true))
                    }
                    .position(x: 35 + CGFloat(index) * 48, y: geometry.size.height * 0.45)
                }
                if selected {
                    Text("Covered marker selected").accessibilityIdentifier("map.fixture.wrongTarget")
                }
            }
        }
    }

    private func marker(_ type: IncidentType, id: String, cleared: Bool = false) -> MapIncidentMarker {
        MapIncidentMarker(id: id, longitude: 12, latitude: 57,
            colorArgb: type.markerColorArgb, iconName: type.symbolName,
            glyphColorArgb: type.glyphColorArgb, reportedCleared: cleared, assetName: type.markerAssetName)
    }
}
#endif
