import SwiftUI

struct GroupDriveScreen: View {
    let coordinator: GroupDriveCoordinator
    let onShowOnMap: (([String]) -> Void)?
    var event: EventSummary? = nil

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                if let event { GroupDriveEventContext(event: event) }
                VStack(alignment: .leading, spacing: KccSpacing.s2) {
                    Text("groupDrive.liveLocationSeparate").font(.headline)
                    Text("groupDrive.liveLocationDisclaimer").font(.body).foregroundStyle(KccTheme.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }.kccContentCard()
                if coordinator.isLoading {
                    ProgressView("groupDrive.loading").frame(maxWidth: .infinity)
                } else if !coordinator.canJoin && !coordinator.isParticipating {
                    SettingsStateCard(title: "groupDrive.memberRequired", message: "groupDrive.rsvpRequired", symbol: "person.2")
                } else {
                    controls
                    Text("\(String(localized: "groupDrive.participantCount")): \(coordinator.activeParticipants.count)")
                        .font(.title3.bold()).accessibilityAddTraits(.isHeader)
                    ForEach(coordinator.activeParticipants) { GroupDriveParticipantRow(participant: $0) }
                }
                if coordinator.loadFailed || coordinator.actionState == .failed {
                    Text("groupDrive.error").font(.body).foregroundStyle(KccPalette.errorRed).kccContentCard()
                }
            }.padding(KccSpacing.s4)
        }
        .navigationTitle(Text("groupDrive.screenTitle"))
        .navigationBarTitleDisplayMode(.inline)
        .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
        .task { coordinator.start() }
    }

    private var controls: some View {
        let busy = coordinator.actionState == .working
        return VStack(alignment: .leading, spacing: KccSpacing.s3) {
            if busy { ProgressView() }
            if let onShowOnMap, !coordinator.activeParticipantUids.isEmpty {
                Button { onShowOnMap(coordinator.activeParticipantUids) } label: {
                    Label("groupDrive.showOnMap", systemImage: "map").frame(maxWidth: .infinity, minHeight: 48)
                }.buttonStyle(.bordered).disabled(busy)
            }
            if coordinator.isParticipating {
                GroupDriveStatusControls(current: coordinator.myStatus, busy: busy, onSelect: coordinator.updateStatus)
                Button(role: .destructive) { coordinator.leave() } label: {
                    ConvoyCommandLabel(title: "groupDrive.leaveButton")
                }.buttonStyle(.bordered).tint(KccPalette.errorRed).disabled(busy)
            } else {
                Button { coordinator.join() } label: { ConvoyCommandLabel(title: "groupDrive.joinButton") }
                    .kccPrimaryAction().disabled(busy || !coordinator.canJoin)
            }
        }.kccContentCard()
    }
}
