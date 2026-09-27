import SwiftUI

/// Safety-net prompt for an auto-save that definitively failed. Normal live
/// session teardown is silent: the drive is auto-saved and kept in History,
/// where the owner can delete it. The sheet is deliberately non-dismissible so
/// a failure cannot silently lose the private route kept for retry or discard.
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
                    if coordinator.state.canRetrySave {
                        Button(action: retry) {
                            Text("savedDrives.retryAction")
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.borderedProminent)
                    } else {
                        Button(role: .destructive, action: discard) {
                            Text("savedDrives.discardAction")
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    if coordinator.state.canRetrySave {
                        Button(role: .destructive, action: discard) {
                            Text("savedDrives.discardAction")
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.bordered)
                    }
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

    private func retry() {
        coordinator.retry()
        dismiss()
    }

    private func discard() {
        coordinator.discardFailed()
        dismiss()
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
