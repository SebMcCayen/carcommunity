import CoreLocation
import MapboxMaps
import SwiftUI

struct SavedDriveDetailScreen: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var coordinator: DriveHistoryCoordinator
    let drive: SavedDrive
    let onDelete: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    Text(DriveDisplay.title(for: drive))
                        .font(.system(size: KccTypeScale.headingLg, weight: KccTypeScale.semibold))
                    Text(DriveDisplay.statsLine(for: drive)).foregroundStyle(.secondary)
                    if let date = drive.startedAt ?? drive.createdAt {
                        LabeledContent("savedDrives.date", value: date.formatted(date: .long, time: .shortened))
                    }
                    route
                    ShareLink(item: DriveShareText.summary(for: drive)) {
                        Label("savedDrives.shareAction", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }.buttonStyle(.borderedProminent)
                    Text("savedDrives.promptPrivacyNote")
                        .font(.system(size: KccTypeScale.bodySm)).foregroundStyle(.secondary)
                    Button(role: .destructive, action: onDelete) {
                        Label("savedDrives.deleteAction", systemImage: "trash")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }.buttonStyle(.bordered)
                    if coordinator.deleteFailed {
                        Text("savedDrives.deleteError").foregroundStyle(KccPalette.errorRed)
                    }
                }.padding(KccSpacing.s6)
            }
            .navigationTitle("savedDrives.detailTitle")
            .toolbar { ToolbarItem(placement: .topBarTrailing) {
                Button("savedDrives.closeButton") { dismiss() }
            }}
        }
        .task(id: drive.id) { await coordinator.loadRoute(for: drive) }
        .onDisappear { coordinator.clearRoute() }
    }

    @ViewBuilder private var route: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("savedDrives.routeOverview")
                .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.semibold))
            switch coordinator.routeState {
            case .idle, .loading:
                ProgressView("savedDrives.routeLoading").frame(maxWidth: .infinity, minHeight: 220)
            case .unavailable:
                ContentUnavailableView("savedDrives.routeUnavailable", systemImage: "map")
                    .frame(maxWidth: .infinity, minHeight: 220)
            case .ready(let points):
                if let token = MapboxConfiguration.accessToken() {
                    DriveReplayMap(accessToken: token, points: points)
                        .frame(height: 280).clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
                        .accessibilityLabel("savedDrives.routeMapFullscreenLabel")
                } else {
                    Text("savedDrives.routeOverviewPlaceholder")
                        .frame(maxWidth: .infinity, minHeight: 220).background(Color(.secondarySystemBackground))
                }
                let markers = DriveRouteDistanceMarkers.markers(for: points)
                if !markers.isEmpty {
                    ScrollView(.horizontal) {
                        HStack {
                            ForEach(markers, id: \.kilometer) { marker in
                                Text(String(format: String(localized: "savedDrives.routeKmMarkerLabel"), marker.kilometer))
                                    .font(.caption).padding(.horizontal, 8).padding(.vertical, 4)
                                    .background(Color(.secondarySystemBackground), in: Capsule())
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
                case .idle, .loading: ProgressView("savedDrives.statsLoading")
                case .failed:
                    ContentUnavailableView {
                        Label("savedDrives.statsError", systemImage: "exclamationmark.triangle")
                    } actions: { Button("savedDrives.retry") { Task { await coordinator.loadStats() } } }
                case .loaded(let stats): statsContent(stats)
                }
            }
            .navigationTitle("savedDrives.statsTitle")
            .toolbar { ToolbarItem(placement: .topBarTrailing) {
                Button("savedDrives.closeButton") { dismiss() }
            }}
        }.task { await coordinator.loadStats() }
    }

    private func statsContent(_ stats: DriveStatsSnapshot) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s4) {
                if stats.totalDrives == 0 { ContentUnavailableView("savedDrives.statsEmpty", systemImage: "car") }
                else {
                    Text("savedDrives.statsAllTime").font(.headline)
                    stat("savedDrives.statsTotalDrives", "\(stats.totalDrives)")
                    stat("savedDrives.statsTotalDistance", DriveFormatters.formatDistance(stats.totalDistanceMeters))
                    stat("savedDrives.statsTotalTime", DriveFormatters.formatDuration(stats.totalDurationSeconds))
                    stat("savedDrives.statsLongest", DriveFormatters.formatDistance(stats.longestDriveMeters))
                    stat("savedDrives.statsAverage", DriveFormatters.formatDistance(stats.averageDriveMeters))
                    stat("savedDrives.statsFastest", DriveFormatters.formatSpeed(stats.fastestAverageSpeedMetersPerSecond))
                    Text("savedDrives.statsThisMonth").font(.headline).padding(.top, KccSpacing.s2)
                    stat("savedDrives.statsTotalDrives", "\(stats.thisMonthDrives)")
                    stat("savedDrives.statsTotalDistance", DriveFormatters.formatDistance(stats.thisMonthDistanceMeters))
                }
            }.padding(KccSpacing.s6)
        }
    }

    private func stat(_ title: LocalizedStringKey, _ value: String) -> some View {
        LabeledContent { Text(value).fontWeight(.semibold) } label: { Text(title) }
            .padding(KccSpacing.s3).background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: KccRadius.sm))
    }
}
