import SwiftUI

struct DriveStatRow: View {
    let title: LocalizedStringKey
    let value: String

    var body: some View {
        SocialFlow {
            Text(title).font(.body).foregroundStyle(KccTheme.secondary)
                .fixedSize(horizontal: false, vertical: true)
            SocialFlowSpacer()
            Text(verbatim: value).font(.body.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct DriveDetailSummary: View {
    let drive: SavedDrive

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text(verbatim: DriveDisplay.title(for: drive)).font(.title2.bold())
                .fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
            DriveStatRow(title: "savedDrives.distance", value: DriveFormatters.formatDistance(drive.distanceMeters))
            DriveStatRow(title: "savedDrives.duration", value: DriveFormatters.formatDuration(drive.durationSeconds))
            DriveStatRow(title: "savedDrives.averageSpeed", value: DriveFormatters.formatSpeed(drive.averageSpeedMetersPerSecond))
            DriveStatRow(title: "savedDrives.maxSpeed", value: DriveFormatters.formatSpeed(drive.maxSpeedMetersPerSecond))
            if let date = drive.startedAt ?? drive.createdAt {
                DriveStatRow(title: "savedDrives.date", value: date.formatted(date: .long, time: .shortened))
            }
            if !drive.convoyMembers.isEmpty {
                Label(DriveDisplay.convoyLine(for: drive), systemImage: "person.2").font(.body).foregroundStyle(KccTheme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }.frame(maxWidth: .infinity, alignment: .leading).kccContentCard()
    }
}

struct DriveStatisticsContent: View {
    let stats: DriveStatsSnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s4) {
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                Text("savedDrives.statsAllTime").font(.title3.bold()).accessibilityAddTraits(.isHeader)
                DriveStatRow(title: "savedDrives.statsTotalDrives", value: String(stats.totalDrives))
                DriveStatRow(title: "savedDrives.statsTotalDistance", value: DriveFormatters.formatDistance(stats.totalDistanceMeters))
                DriveStatRow(title: "savedDrives.statsTotalTime", value: DriveFormatters.formatDuration(stats.totalDurationSeconds))
                DriveStatRow(title: "savedDrives.statsLongest", value: DriveFormatters.formatDistance(stats.longestDriveMeters))
                DriveStatRow(title: "savedDrives.statsAverage", value: DriveFormatters.formatDistance(stats.averageDriveMeters))
                DriveStatRow(title: "savedDrives.statsFastest", value: DriveFormatters.formatSpeed(stats.fastestAverageSpeedMetersPerSecond))
            }.kccContentCard()
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                Text("savedDrives.statsThisMonth").font(.title3.bold()).accessibilityAddTraits(.isHeader)
                DriveStatRow(title: "savedDrives.statsTotalDrives", value: String(stats.thisMonthDrives))
                DriveStatRow(title: "savedDrives.statsTotalDistance", value: DriveFormatters.formatDistance(stats.thisMonthDistanceMeters))
            }.kccContentCard()
        }
    }
}

struct DriveRecordingStatsCard: View {
    let summary: DriveRecordingSummary

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            DriveStatRow(title: "savedDrives.distance", value: DriveFormatters.formatDistance(summary.distanceMeters))
            DriveStatRow(title: "savedDrives.duration", value: DriveFormatters.formatDuration(summary.durationSeconds))
            DriveStatRow(title: "savedDrives.averageSpeed", value: DriveFormatters.formatSpeed(summary.averageSpeedMetersPerSecond))
        }.kccContentCard()
    }
}

struct DriveFilterControls: View {
    @Binding var criteria: DriveFilterCriteria

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            EditorTextField(title: "savedDrives.filterSearchLabel", text: $criteria.query)
            Menu {
                Picker("savedDrives.filterPeriod", selection: $criteria.dateRange) {
                    Text("savedDrives.filterAll").tag(DriveDateRange.all)
                    Text("savedDrives.filterThisWeek").tag(DriveDateRange.thisWeek)
                    Text("savedDrives.filterThisMonth").tag(DriveDateRange.thisMonth)
                }
            } label: {
                EditorSelectionLabel(title: "savedDrives.filterPeriod", value: Text(periodTitle), prompt: "savedDrives.filterAll")
            }.buttonStyle(.plain)
            Menu {
                Picker("savedDrives.filterDistance", selection: $criteria.distanceBand) {
                    Text("savedDrives.filterAll").tag(DriveDistanceBand.all)
                    Text("savedDrives.filterUnder10").tag(DriveDistanceBand.under10)
                    Text("savedDrives.filter10to50").tag(DriveDistanceBand.from10To50)
                    Text("savedDrives.filterOver50").tag(DriveDistanceBand.over50)
                }
            } label: {
                EditorSelectionLabel(title: "savedDrives.filterDistance", value: Text(distanceTitle), prompt: "savedDrives.filterAll")
            }.buttonStyle(.plain)
            Menu {
                Picker("savedDrives.filterSort", selection: $criteria.sort) {
                    Text("savedDrives.sortNewest").tag(DriveSort.newest)
                    Text("savedDrives.sortLongest").tag(DriveSort.longest)
                    Text("savedDrives.sortFastest").tag(DriveSort.fastestAverage)
                }
            } label: {
                EditorSelectionLabel(title: "savedDrives.filterSort", value: Text(sortTitle), prompt: "savedDrives.sortNewest")
            }.buttonStyle(.plain)
            if criteria.activeFilterCount > 0 {
                Button { criteria = .init() } label: { Text("savedDrives.filterClear").frame(minHeight: 48) }.buttonStyle(.bordered)
            }
        }.kccContentCard()
    }

    private var periodTitle: LocalizedStringKey {
        switch criteria.dateRange {
        case .all: "savedDrives.filterAll"
        case .thisWeek: "savedDrives.filterThisWeek"
        case .thisMonth: "savedDrives.filterThisMonth"
        }
    }
    private var distanceTitle: LocalizedStringKey {
        switch criteria.distanceBand {
        case .all: "savedDrives.filterAll"
        case .under10: "savedDrives.filterUnder10"
        case .from10To50: "savedDrives.filter10to50"
        case .over50: "savedDrives.filterOver50"
        }
    }
    private var sortTitle: LocalizedStringKey {
        switch criteria.sort {
        case .newest: "savedDrives.sortNewest"
        case .longest: "savedDrives.sortLongest"
        case .fastestAverage: "savedDrives.sortFastest"
        }
    }
}
