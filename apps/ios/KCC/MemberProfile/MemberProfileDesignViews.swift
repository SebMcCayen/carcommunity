import SwiftUI

/// Receives only the existing public projection and already-resolved avatar.
struct MemberProfileHeader: View {
    let profile: MemberProfile
    let pointsBalance: Int64
    let avatarURL: URL?

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            AsyncImage(url: avatarURL) { phase in
                if let image = phase.image { image.resizable().scaledToFill() }
                else {
                    Image(systemName: "person.fill").font(.system(size: 40))
                        .foregroundStyle(KccTheme.driver)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(KccTheme.subtle)
                }
            }
            .frame(width: 92, height: 92).clipShape(Circle()).accessibilityHidden(true)
            Text(verbatim: profile.displayName.trimmedNonBlank ?? String(localized: "memberProfile.unknownMember"))
                .font(.title2.bold()).fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            SocialFlow {
                Image(systemName: "crown.fill").foregroundStyle(KccTheme.driver).accessibilityHidden(true)
                Text(verbatim: FriendPointsFormat.grouped(pointsBalance)).font(.title.bold())
                Text("profile.pointsTitle").font(.body).foregroundStyle(KccTheme.secondary)
            }
            if let bio = profile.bio.trimmedNonBlank {
                Text(verbatim: bio).font(.body).fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }
}

struct MemberVehicleSummary: View {
    let vehicle: Vehicle

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text(verbatim: VehicleDisplay.headline(vehicle, otherLabel: String(localized: "garage.otherNotListed")))
                .font(.headline).fixedSize(horizontal: false, vertical: true)
            if vehicle.isMainCar {
                Label("memberProfile.mainCar", systemImage: "checkmark.circle")
                    .font(.footnote).foregroundStyle(KccTheme.driver)
            }
            Text(LocalizedStringKey(vehicle.powertrain.localizationKey)).font(.body).foregroundStyle(KccTheme.secondary)
            if let plate = vehicle.registrationPlate.trimmedNonBlank {
                Text(verbatim: String.localizedStringWithFormat(
                    NSLocalizedString("memberProfile.registrationPlate", comment: "Public plate"), plate
                )).font(.footnote).foregroundStyle(KccTheme.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(KccSpacing.s3)
        .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.md))
    }
}

/// Held awards only: a public profile never reads the owner's private counters
/// or suggests that unearned tiers have been awarded.
struct MemberBadgeSummary: View {
    let badge: Badge

    var body: some View {
        HStack(alignment: .top, spacing: KccSpacing.s3) {
            BadgeMedallion(tier: rungForBadgeKey(badge.key)?.rung.tier, earned: true)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                Group {
                    if let key = BadgeStrings.badgeNameKey(for: badge.key) { Text(LocalizedStringKey(key)) }
                    else { Text(verbatim: badge.fallbackName ?? badge.key) }
                }
                .font(.headline).fixedSize(horizontal: false, vertical: true)
                if let date = badge.awardedAt {
                    Text(verbatim: BadgeText.awardedOn(date: date)).font(.footnote).foregroundStyle(KccTheme.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(KccSpacing.s3)
        .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.md))
        .accessibilityElement(children: .combine)
    }
}
