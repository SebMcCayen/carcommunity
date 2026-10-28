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
                        DriveRecordingStatsCard(summary: summary)
                    }
                    Text(errorKey)
                        .foregroundStyle(KccPalette.errorRed)
                    if coordinator.state.canRetrySave {
                        Button(action: retry) {
                            Text("savedDrives.retryAction")
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }
                        .kccPrimaryAction()
                    } else {
                        Button(role: .destructive, action: discard) {
                            Text("savedDrives.discardAction")
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }
                        .buttonStyle(.borderedProminent).tint(KccPalette.errorRed)
                    }
                    if coordinator.state.canRetrySave {
                        Button(role: .destructive, action: discard) {
                            Text("savedDrives.discardAction")
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }
                        .buttonStyle(.bordered).tint(KccPalette.errorRed)
                    }
                }
                .padding(KccSpacing.s4)
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
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

}
