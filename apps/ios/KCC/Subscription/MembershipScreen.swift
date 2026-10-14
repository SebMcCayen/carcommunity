import SwiftUI

/// iOS membership explainer. StoreKit purchase support is not composed yet,
/// so this screen deliberately describes the plans and backend-known status
/// without presenting a purchase control that cannot complete.
struct MembershipScreen: View {
    let access: AccountAccess

    var body: some View {
        ScrollView {
            VStack(spacing: KccSpacing.s4) {
                membershipCard
                planCard(title: "subscription.plusTitle", body: "subscription.plusBody")
                planCard(title: "subscription.supporterTitle", body: "subscription.supporterBody")

                Text("subscription.purchaseHandledExternally")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                if access.isRestricted {
                    Text("subscription.accessLimitedBySuspension")
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
            .padding(KccSpacing.s4)
        }
        .navigationTitle("subscription.screenTitle")
        .accessibilityIdentifier("membership.screen")
    }

    private var membershipCard: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("subscription.currentPlanTitle")
                .font(.headline)
            Text(LocalizedStringKey(
                access.activeMember
                    ? "subscription.currentEntitlementMember"
                    : "subscription.currentEntitlementFree"
            ))
                .foregroundStyle(.secondary)
            Text("subscription.memberRequiredBody")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(KccSpacing.s4)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: KccRadius.lg))
    }

    private func planCard(title: LocalizedStringKey, body: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text(title).font(.headline)
            Text(body)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(KccSpacing.s4)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: KccRadius.lg))
    }
}
