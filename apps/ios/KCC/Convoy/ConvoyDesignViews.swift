import SwiftUI

struct ConvoySummaryContent: View {
    let convoy: ConvoyItem
    var showsDisclosure = false

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            HStack(alignment: .top, spacing: KccSpacing.s3) {
                Text(verbatim: convoy.title ?? String(localized: "convoy.untitled"))
                    .font(.title3.bold()).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if showsDisclosure {
                    Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(KccTheme.secondary).accessibilityHidden(true)
                }
            }
            Text(ConvoyDesignStrings.statusKey(convoy.status)).font(.footnote.weight(.semibold))
                .foregroundStyle(KccTheme.driver).padding(KccSpacing.s2)
                .background(KccTheme.subtle, in: Capsule())
            Text(verbatim: String.localizedStringWithFormat(
                NSLocalizedString("convoy.barMembers", comment: "Accepted convoy members"), convoy.acceptedMemberCount
            )).font(.subheadline).foregroundStyle(KccTheme.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
    }
}

struct ConvoyFriendChoiceLabel: View {
    let friend: FriendSummary
    let selected: Bool

    var body: some View {
        HStack(alignment: .top, spacing: KccSpacing.s3) {
            SocialMemberLabel(name: friend.displayName)
            Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                .font(.system(size: 24)).foregroundStyle(selected ? KccTheme.driver : KccTheme.secondary)
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .kccContentCard(border: selected ? KccTheme.driver : KccTheme.border)
        .contentShape(Rectangle())
    }
}

struct ConvoyCommandLabel: View {
    let title: LocalizedStringKey
    var working = false

    var body: some View {
        HStack(spacing: KccSpacing.s2) {
            if working { ProgressView().accessibilityHidden(true) }
            Text(title).font(.body.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, minHeight: 48)
        .contentShape(Rectangle())
    }
}

struct ConvoyMemberSummary: View {
    let member: ConvoyMember
    var waiting = false
    var showsInviteStatus = true

    var body: some View {
        HStack(alignment: .top, spacing: KccSpacing.s3) {
            Image(systemName: member.role == .owner ? "crown.fill" : "person.fill")
                .font(.system(size: 20)).foregroundStyle(KccTheme.driver)
                .frame(width: 40, height: 40).background(KccTheme.subtle, in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: KccSpacing.s2) {
                Text(verbatim: member.displayName.trimmedNonBlank ?? String(localized: "convoy.unknownMember"))
                    .font(.headline).fixedSize(horizontal: false, vertical: true)
                if member.role == .owner { Text("convoy.roleOwner").font(.footnote).foregroundStyle(KccTheme.secondary) }
                if waiting { Text("convoy.barMemberWaiting").font(.footnote).foregroundStyle(KccTheme.secondary) }
                else if showsInviteStatus {
                    Text(ConvoyDesignStrings.inviteKey(member.inviteStatus)).font(.footnote).foregroundStyle(KccTheme.secondary)
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Callbacks are supplied only after the existing caller checks profile and
/// location eligibility. This widget never fetches identity or position data.
struct ConvoyMemberActionCard: View {
    let member: ConvoyMember
    let waiting: Bool
    let locationAvailable: Bool
    var onProfile: (() -> Void)?
    var onCenter: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            ConvoyMemberSummary(member: member, waiting: waiting, showsInviteStatus: false)
            if onProfile != nil || onCenter != nil {
                SocialFlow {
                    if let onProfile {
                        Button(action: onProfile) {
                            Label("convoy.barMemberMenuProfile", systemImage: "person.crop.circle").frame(minHeight: 48)
                        }.buttonStyle(.bordered)
                    }
                    if let onCenter {
                        Button(action: onCenter) {
                            Label("convoy.barMemberMenuGoToLocation", systemImage: "scope").frame(minHeight: 48)
                        }.buttonStyle(.bordered).disabled(!locationAvailable)
                    }
                }
            }
            if onCenter != nil && !locationAvailable {
                Text("convoy.barMemberLocationUnavailable").font(.footnote).foregroundStyle(KccTheme.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }
}

struct ConvoyActionFeedback: View {
    let error: ConvoyActionError
    let onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: KccSpacing.s3) {
            Text(ConvoyManagementStrings.errorKey(error)).font(.body).foregroundStyle(KccPalette.errorRed)
                .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onDismiss) {
                Image(systemName: "xmark").font(.system(size: 16, weight: .semibold))
                    .frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel(Text("convoy.close"))
        }.kccContentCard()
    }
}

struct ConvoyRecapCard: View {
    let recap: ConvoyRecapState

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text("convoy.summaryTitle").font(.title3.bold()).accessibilityAddTraits(.isHeader)
            recapLine("convoy.summaryDuration", value: DriveFormatters.formatDuration(recap.durationSeconds))
            recapLine("convoy.summaryParticipants", value: String(recap.participantCount))
            recapLine("convoy.summaryDistance", value: recap.distanceMeters.map { DriveFormatters.formatDistance($0) }
                ?? String(localized: "convoy.summaryDistanceUnavailable"))
            if !recap.participants.isEmpty {
                Text("convoy.summaryWho").font(.headline).accessibilityAddTraits(.isHeader)
                ForEach(recap.participants) { member in
                    SocialMemberLabel(name: member.displayName, fallback: "convoy.unknownMember")
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).kccContentCard()
    }

    private func recapLine(_ title: LocalizedStringKey, value: String) -> some View {
        SocialFlow {
            Text(title).foregroundStyle(KccTheme.secondary)
            SocialFlowSpacer()
            Text(verbatim: value).fontWeight(.semibold).fixedSize(horizontal: false, vertical: true)
        }.font(.body)
    }
}

enum ConvoyDesignStrings {
    static func statusKey(_ status: ConvoyStatus) -> LocalizedStringKey {
        switch status {
        case .forming: "convoy.statusForming"
        case .active: "convoy.statusActive"
        case .ended: "convoy.statusEnded"
        }
    }
    static func inviteKey(_ status: ConvoyInviteStatus) -> LocalizedStringKey {
        switch status {
        case .invited: "convoy.inviteInvited"
        case .accepted: "convoy.inviteAccepted"
        case .declined: "convoy.inviteDeclined"
        }
    }
}
