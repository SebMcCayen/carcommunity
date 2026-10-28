import SwiftUI

struct MembershipScreen: View {
    @Environment(\.openURL) private var openURL

    let access: AccountAccess
    let coordinator: SubscriptionCoordinator

    var body: some View {
        ScrollView {
            VStack(spacing: KccSpacing.s4) {
                membershipCard
                planCard(
                    productID: plusMonthlyProductID,
                    title: "subscription.plusTitle",
                    body: "subscription.plusBody"
                )
                planCard(
                    productID: supporterMonthlyProductID,
                    title: "subscription.supporterTitle",
                    body: "subscription.supporterBody"
                )

                statusView

                Button { Task { await coordinator.restore() } } label: {
                    Text("subscription.restoreAction").frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered)
                .disabled(coordinator.status.isBusy)

                if access.activeMember || coordinator.currentTier != nil {
                    Button {
                        guard let url = URL(string: "https://apps.apple.com/account/subscriptions") else { return }
                        openURL(url)
                    } label: { Text("subscription.manageAction").frame(maxWidth: .infinity, minHeight: 48) }
                    .buttonStyle(.bordered)
                    Text("subscription.manageBody")
                        .font(.footnote)
                        .foregroundStyle(KccTheme.secondary)
                }

                if access.isRestricted {
                    Text("subscription.accessLimitedBySuspension")
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
            .padding(KccSpacing.s4)
        }
        .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
        .navigationTitle("subscription.screenTitle")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("membership.screen")
        .task { await coordinator.start() }
        .onChange(of: access.activeMember) { _, _ in
            Task { await coordinator.reloadAndReconcile() }
        }
    }

    private var membershipCard: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("subscription.currentPlanTitle").font(.headline)
            Text(currentEntitlementKey)
                .foregroundStyle(KccTheme.secondary)
            Text("subscription.memberRequiredBody")
                .font(.footnote)
                .foregroundStyle(KccTheme.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }

    private var currentEntitlementKey: LocalizedStringKey {
        switch coordinator.currentTier {
        case .supporter: "subscription.currentEntitlementSupporter"
        case .plus: "subscription.currentEntitlementPlus"
        case .community: "subscription.currentEntitlementFree"
        case nil:
            access.activeMember
                ? "subscription.currentEntitlementMember"
                : "subscription.currentEntitlementFree"
        }
    }

    private func planCard(
        productID: String,
        title: LocalizedStringKey,
        body: LocalizedStringKey
    ) -> some View {
        let product = coordinator.products.first { $0.id == productID }
        let expectedTier = productID == supporterMonthlyProductID ? SubscriptionTier.supporter : .plus
        let isCurrent = coordinator.currentTier == expectedTier
        return VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text(title).font(.headline)
            Text(body)
                .font(.subheadline)
                .foregroundStyle(KccTheme.secondary)
            if let product {
                Text(product.displayPrice)
                    .font(.subheadline.weight(.semibold))
                Button { Task { await coordinator.purchase(productID) } } label: {
                    EditorSubmitLabel(title: isCurrent ? "subscription.currentPlanAction" : actionKey(for: productID))
                }
                .kccPrimaryAction()
                .disabled(isCurrent || !coordinator.canPurchase || access.isRestricted)
                .frame(maxWidth: .infinity, alignment: .leading)
            } else if coordinator.status == .loading {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }

    private func actionKey(for productID: String) -> LocalizedStringKey {
        if productID == supporterMonthlyProductID, coordinator.currentTier == .plus {
            return "subscription.upgradeSupporterAction"
        }
        if productID == plusMonthlyProductID, coordinator.currentTier == .supporter {
            return "subscription.downgradePlusAction"
        }
        return productID == supporterMonthlyProductID
            ? "subscription.subscribeSupporterAction"
            : "subscription.subscribePlusAction"
    }

    @ViewBuilder
    private var statusView: some View {
        if let key = statusKey {
            Text(key)
                .font(.footnote)
                .foregroundStyle(isFailure ? Color.red : Color.secondary)
                .multilineTextAlignment(.center)
        }
        if case .failed = coordinator.status {
            Button { Task { await coordinator.reloadAndReconcile() } } label: { Text("notifications.retry").frame(minHeight: 48) }
            .buttonStyle(.bordered)
        }
    }

    private var isFailure: Bool {
        if case .failed = coordinator.status { return true }
        return false
    }

    private var statusKey: LocalizedStringKey? {
        switch coordinator.status {
        case .idle: nil
        case .loading: "subscription.statusConnecting"
        case .ready: "subscription.statusReady"
        case .purchasing: "subscription.statusPurchasing"
        case .verifying: "subscription.statusVerifying"
        case .success: "subscription.statusSuccess"
        case .pending: "subscription.statusPending"
        case .failed(let reason):
            switch reason {
            case .unavailable: "subscription.statusUnavailable"
            case .productUnavailable: "subscription.errorProductUnavailable"
            case .purchase: "subscription.errorPurchase"
            case .verification: "subscription.errorVerification"
            case .inactivePurchase: "subscription.errorInactivePurchase"
            }
        }
    }
}
