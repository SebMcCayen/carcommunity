import SwiftUI

struct GroupDriveScreen: View {
    let coordinator: GroupDriveCoordinator
    let onShowOnMap: (([String]) -> Void)?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                Text("groupDrive.liveLocationSeparate")
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(.secondary)

                if coordinator.isLoading {
                    ProgressView("groupDrive.loading")
                        .frame(maxWidth: .infinity)
                } else if !coordinator.canJoin && !coordinator.isParticipating {
                    infoCard
                } else {
                    controls
                    roster
                }

                if coordinator.loadFailed || coordinator.actionState == .failed {
                    Text("groupDrive.error")
                        .foregroundStyle(KccPalette.errorRed)
                }
            }
            .padding(KccSpacing.s4)
        }
        .navigationTitle(Text("groupDrive.screenTitle"))
        .navigationBarTitleDisplayMode(.inline)
        .task { coordinator.start() }
    }

    @ViewBuilder
    private var controls: some View {
        let busy = coordinator.actionState == .working
        Text(participantCountText(coordinator.activeParticipants.count))
            .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))

        if let onShowOnMap, !coordinator.activeParticipantUids.isEmpty {
            Button {
                onShowOnMap(coordinator.activeParticipantUids)
            } label: {
                Label("groupDrive.showOnMap", systemImage: "map")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(busy)
        }

        if coordinator.isParticipating {
            Text("groupDrive.setStatus")
                .font(.system(size: KccTypeScale.bodySm, weight: KccTypeScale.semibold))
            ForEach(GroupDriveStatus.updatable, id: \.self) { status in
                statusButton(status, busy: busy)
            }
            Text("groupDrive.safeDrivingStopFirst")
                .font(.system(size: KccTypeScale.caption))
                .foregroundStyle(.secondary)
            Button("groupDrive.leaveButton", role: .destructive) { coordinator.leave() }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
                .disabled(busy)
        } else {
            Button("groupDrive.joinButton") { coordinator.join() }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
                .disabled(busy || !coordinator.canJoin)
        }
    }

    @ViewBuilder
    private var roster: some View {
        if !coordinator.activeParticipants.isEmpty {
            VStack(alignment: .leading, spacing: KccSpacing.s2) {
                ForEach(coordinator.activeParticipants) { participant in
                    HStack {
                        Text(displayName(participant))
                        Spacer()
                        Text(statusKey(participant.status))
                            .foregroundStyle(.secondary)
                    }
                    if participant.id != coordinator.activeParticipants.last?.id { Divider() }
                }
            }
            .padding(KccSpacing.s3)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: KccSpacing.s2))
        }
    }

    private var infoCard: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            Text("groupDrive.memberRequired")
                .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
            Text("groupDrive.rsvpRequired").foregroundStyle(.secondary)
        }
        .padding(KccSpacing.s3)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: KccSpacing.s2))
    }

    @ViewBuilder
    private func statusButton(_ status: GroupDriveStatus, busy: Bool) -> some View {
        let button = Button { coordinator.updateStatus(status) } label: {
            Text(statusKey(status)).frame(maxWidth: .infinity)
        }
        .disabled(busy)
        if coordinator.myStatus == status {
            button.buttonStyle(.borderedProminent)
        } else {
            button.buttonStyle(.bordered)
        }
    }

    private func displayName(_ participant: GroupDriveParticipant) -> String {
        let name = participant.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
        return name?.isEmpty == false ? name! : String(localized: "events.attendeesUnknownMember")
    }

    private func statusKey(_ status: GroupDriveStatus) -> LocalizedStringKey {
        switch status {
        case .joined: "groupDrive.statusJoined"
        case .onTheWay: "groupDrive.statusOnTheWay"
        case .arrived: "groupDrive.statusArrived"
        case .left: "groupDrive.notParticipating"
        }
    }

    private func participantCountText(_ count: Int) -> String {
        "\(String(localized: "groupDrive.participantCount")): \(count)"
    }
}
