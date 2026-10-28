import CoreLocation
import MapboxMaps
import SwiftUI

struct SavedDriveDetailScreen: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var coordinator: DriveHistoryCoordinator
    let drive: SavedDrive
    let sharingEnabled: Bool
    let onDelete: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    DriveDetailSummary(drive: drive)
                    route
                    if sharingEnabled {
                        ShareLink(item: DriveShareText.summary(for: drive)) {
                            Label("savedDrives.shareAction", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }.kccPrimaryAction()
                    }
                    Text("savedDrives.promptPrivacyNote")
                        .font(.footnote).foregroundStyle(KccTheme.secondary)
                    Button(role: .destructive, action: onDelete) {
                        Label("savedDrives.deleteAction", systemImage: "trash")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }.buttonStyle(.bordered).tint(KccPalette.errorRed)
                    .disabled(coordinator.deletingRideId != nil)
                    if coordinator.deleteFailed {
                        Text("savedDrives.deleteError").foregroundStyle(KccPalette.errorRed)
                    }
                }.padding(KccSpacing.s4)
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle("savedDrives.detailTitle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) {
                Button { dismiss() } label: { Text("savedDrives.closeButton").frame(minHeight: 48) }
            }}
        }
        .task(id: drive.id) { await coordinator.loadRoute(for: drive) }
        .onDisappear { coordinator.clearRoute() }
    }

    @ViewBuilder private var route: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("savedDrives.routeOverview")
                .font(.title3.bold()).accessibilityAddTraits(.isHeader)
            switch coordinator.routeState {
            case .idle, .loading:
                ProgressView("savedDrives.routeLoading").frame(maxWidth: .infinity, minHeight: 220)
            case .unavailable:
                SettingsStateCard(title: "savedDrives.routeOverview", message: "savedDrives.routeUnavailable", symbol: "map")
            case .ready(let points):
                if let token = MapboxConfiguration.accessToken() {
                    DriveReplayMap(accessToken: token, points: points)
                        .frame(height: 280).clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
                        .accessibilityLabel("savedDrives.routeMapFullscreenLabel")
                } else {
                    Text("savedDrives.routeOverviewPlaceholder")
                        .frame(maxWidth: .infinity, minHeight: 220).background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.md))
                }
                let markers = DriveRouteDistanceMarkers.markers(for: points)
                if !markers.isEmpty {
                    ScrollView(.horizontal) {
                        HStack {
                            ForEach(markers, id: \.kilometer) { marker in
                                Text(String(format: String(localized: "savedDrives.routeKmMarkerLabel"), marker.kilometer))
                                    .font(.footnote).padding(KccSpacing.s2)
                                    .background(KccTheme.subtle, in: Capsule())
                            }
                        }
                    }
                }
            }
        }
    }
}

private struct DriveReplayMap: View {
    let points: [DriveRoutePoint]
    @State private var viewport: Viewport

    init(accessToken: String, points: [DriveRoutePoint]) {
        MapboxOptions.accessToken = accessToken
        self.points = points
        let center = Self.center(points)
        _viewport = State(initialValue: .camera(center: center, zoom: Self.zoom(points), bearing: 0, pitch: 0))
    }

    var body: some View {
        MapboxMaps.Map(viewport: $viewport) {
            PolylineAnnotation(
                id: "saved-drive-route",
                lineCoordinates: points.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
            )
            .lineColor(UIColor.systemBlue).lineWidth(5).lineOpacity(0.9).lineJoin(.round)
            if let first = points.first {
                CircleAnnotation(centerCoordinate: CLLocationCoordinate2D(latitude: first.latitude, longitude: first.longitude))
                    .circleColor(UIColor.systemGreen).circleRadius(7).circleStrokeColor(.white).circleStrokeWidth(2)
            }
            if let last = points.last {
                CircleAnnotation(centerCoordinate: CLLocationCoordinate2D(latitude: last.latitude, longitude: last.longitude))
                    .circleColor(UIColor.systemRed).circleRadius(7).circleStrokeColor(.white).circleStrokeWidth(2)
            }
        }.mapStyle(.standard)
    }

    private static func center(_ points: [DriveRoutePoint]) -> CLLocationCoordinate2D {
        let latitudes = points.map(\.latitude), longitudes = points.map(\.longitude)
        return CLLocationCoordinate2D(latitude: ((latitudes.min() ?? 57.4872) + (latitudes.max() ?? 57.4872)) / 2,
                                      longitude: ((longitudes.min() ?? 12.0761) + (longitudes.max() ?? 12.0761)) / 2)
    }

    private static func zoom(_ points: [DriveRoutePoint]) -> Double {
        guard let minLat = points.map(\.latitude).min(), let maxLat = points.map(\.latitude).max(),
              let minLon = points.map(\.longitude).min(), let maxLon = points.map(\.longitude).max()
        else { return 10 }
        let span = max(maxLat - minLat, maxLon - minLon)
        if span < 0.01 { return 13 }
        if span < 0.05 { return 11 }
        if span < 0.2 { return 9 }
        return 7
    }
}

struct DriveStatsScreen: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var coordinator: DriveHistoryCoordinator

    var body: some View {
        NavigationStack {
            Group {
                switch coordinator.statsState {
                case .idle, .loading:
                    if case .unavailable = coordinator.state {
                        ScrollView {
                            SettingsStateCard(title: "savedDrives.statsTitle", message: "savedDrives.error", symbol: "chart.bar")
                                .accessibilityElement(children: .contain).accessibilityIdentifier("myDrives.stats.unavailable")
                                .padding(KccSpacing.s4)
                        }
                    } else { ProgressView("savedDrives.statsLoading") }
                case .failed:
                    ScrollView {
                        SettingsStateCard(title: "savedDrives.statsTitle", message: "savedDrives.statsError", symbol: "exclamationmark.circle",
                            retryTitle: "savedDrives.retry", onRetry: { Task { await coordinator.loadStats() } }).padding(KccSpacing.s4)
                    }
                case .loaded(let stats): statsContent(stats)
                }
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle("savedDrives.statsTitle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) {
                Button { dismiss() } label: { Text("savedDrives.closeButton").frame(minHeight: 48) }.accessibilityIdentifier("myDrives.stats.close")
            }}
        }.task { await coordinator.loadStats() }
    }

    private func statsContent(_ stats: DriveStatsSnapshot) -> some View {
        ScrollView {
            Group {
                if stats.totalDrives == 0 {
                    SettingsStateCard(title: "savedDrives.statsTitle", message: "savedDrives.statsEmpty", symbol: "car")
                } else { DriveStatisticsContent(stats: stats) }
            }.padding(KccSpacing.s4)
        }
    }
}
