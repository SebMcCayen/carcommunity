import SwiftUI

struct GroupDriveEventContext: View {
    let event: EventSummary

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text(verbatim: event.title).font(.title3.bold()).fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            EventScheduleContent(event: event, detailed: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading).kccContentCard()
    }
}

struct GroupDriveParticipantRow: View {
    let participant: GroupDriveParticipant

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            SocialMemberLabel(name: participant.displayName, fallback: "events.attendeesUnknownMember")
            Text(GroupDriveDesignStrings.statusKey(participant.status)).font(.body).foregroundStyle(KccTheme.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading).kccContentCard()
        .accessibilityElement(children: .combine)
    }
}

/// Selection remains authoritative: a tap forwards a command and never
/// changes the selected status before the coordinator's own-status stream does.
struct GroupDriveStatusControls: View {
    let current: GroupDriveStatus?
    let busy: Bool
    let onSelect: (GroupDriveStatus) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text("groupDrive.setStatus").font(.headline).accessibilityAddTraits(.isHeader)
            ForEach(GroupDriveStatus.updatable, id: \.self) { status in
                Button { onSelect(status) } label: {
                    HStack(spacing: KccSpacing.s3) {
                        Text(GroupDriveDesignStrings.statusKey(status)).font(.body.weight(.semibold))
                            .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                        if current == status {
                            Image(systemName: "checkmark.circle.fill").font(.system(size: 20)).accessibilityHidden(true)
                        }
                    }
                    .padding(KccSpacing.s3).frame(maxWidth: .infinity, minHeight: 48)
                    .foregroundStyle(current == status ? KccTheme.onDriver : KccTheme.text)
                    .background(current == status ? KccTheme.driver : KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.md))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain).disabled(busy)
                .accessibilityAddTraits(current == status ? [.isSelected] : [])
            }
            Text("groupDrive.safeDrivingStopFirst").font(.footnote).foregroundStyle(KccTheme.secondary)
        }
    }
}

enum GroupDriveDesignStrings {
    static func statusKey(_ status: GroupDriveStatus) -> LocalizedStringKey {
        switch status {
        case .joined: "groupDrive.statusJoined"
        case .onTheWay: "groupDrive.statusOnTheWay"
        case .arrived: "groupDrive.statusArrived"
        case .left: "groupDrive.notParticipating"
        }
    }
}
