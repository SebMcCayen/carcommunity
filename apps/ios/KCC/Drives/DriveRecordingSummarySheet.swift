import SwiftUI

struct DriveRecordingSummarySheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var confirmsDiscard = false

    let coordinator: DriveRecordingCoordinator

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    Text("savedDrives.promptBody")
                        .foregroundStyle(.secondary)
                    if let summary = coordinator.state.summary {
                        summaryCard(summary)
                    }
                    TextField("savedDrives.recordTitleLabel", text: $title)
                        .textFieldStyle(.roundedBorder)
                        .disabled(isWorking || isSaved)
                    Label("savedDrives.promptPrivacyNote", systemImage: "lock.fill")
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(.secondary)

                    if case .failed(_, let code) = coordinator.state {
                        Text(code == .permissionDenied
                             ? "savedDrives.memberRequired"
                             : "savedDrives.saveError")
                            .foregroundStyle(KccPalette.errorRed)
                    }

                    if isSaved {
                        Label("savedDrives.saveSuccess", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                        Button("savedDrives.closeButton") {
                            coordinator.finishSummary()
                            dismiss()
                        }
                        .buttonStyle(.borderedProminent)
                        .frame(maxWidth: .infinity)
                    } else {
                        Button {
                            Task { await coordinator.save(title: title) }
                        } label: {
                            HStack {
                                if isWorking { ProgressView() }
                                Text(isWorking ? "savedDrives.savingProgress" : "savedDrives.saveAction")
                            }
                            .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(isWorking)

                        Button("savedDrives.discardAction", role: .destructive) {
                            confirmsDiscard = true
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .disabled(isWorking)
                    }
                }
                .padding(KccSpacing.s6)
            }
            .navigationTitle("savedDrives.promptTitle")
        }
        .interactiveDismissDisabled()
        .confirmationDialog(
            "savedDrives.discardConfirmTitle",
            isPresented: $confirmsDiscard,
            titleVisibility: .visible
        ) {
            Button("savedDrives.discardConfirmAction", role: .destructive) {
                coordinator.discard()
                dismiss()
            }
            Button("savedDrives.discardConfirmCancel", role: .cancel) {}
        } message: {
            Text("savedDrives.discardConfirmBody")
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

    private var isWorking: Bool {
        if case .saving = coordinator.state { return true }
        return false
    }

    private var isSaved: Bool {
        if case .saved = coordinator.state { return true }
        return false
    }
}
