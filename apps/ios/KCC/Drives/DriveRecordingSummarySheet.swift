import SwiftUI

/// Safety-net prompt for an auto-save that definitively failed. Normal live
/// session teardown is silent: the drive is auto-saved and kept in History,
/// where the owner can delete it. The sheet is deliberately non-dismissible so
/// a transient failure cannot silently lose the private route kept for retry.
struct DriveRecordingSummarySheet: View {
    @Environment(\.dismiss) private var dismiss
    let coordinator: DriveRecordingCoordinator

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    if let summary = coordinator.state.summary {
                        summaryCard(summary)
                    }
                    Text(errorKey)
                        .foregroundStyle(KccPalette.errorRed)
                    Button(action: primaryAction) {
                        Text(primaryKey)
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(KccSpacing.s6)
            }
            .navigationTitle("savedDrives.saveFailedTitle")
        }
        .interactiveDismissDisabled()
    }

    private var isPermanentRefusal: Bool {
        if case .failed(_, let code) = coordinator.state { return code == .permissionDenied }
        return false
    }

    private var errorKey: LocalizedStringKey {
        isPermanentRefusal ? "savedDrives.memberRequired" : "savedDrives.saveError"
    }

    private var primaryKey: LocalizedStringKey {
        isPermanentRefusal ? "savedDrives.closeButton" : "savedDrives.retryAction"
    }

    private func primaryAction() {
        if isPermanentRefusal {
            coordinator.discardFailed()
            dismiss()
        } else {
            coordinator.retry()
            dismiss()
        }
    }

    private func summaryCard(_ summary: DriveRecordingSummary) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            stat("savedDrives.distance", DriveFormatters.formatDistance(summary.distanceMeters))
            stat("savedDrives.duration", DriveFormatters.formatDuration(summary.durationSeconds))
            stat("savedDrives.averageSpeed", DriveFormatters.formatSpeed(summary.averageSpeedMetersPerSecond))
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
    }

    private func stat(_ label: LocalizedStringKey, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).fontWeight(.semibold)
        }
    }
}
