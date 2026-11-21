import SwiftUI
import StoreKit

struct MembershipScreen: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.locale) private var locale
    @State private var showingSubscriptions = false

    let access: AccountAccess
    let coordinator: SubscriptionCoordinator

    var body: some View {
        ScrollView {
            VStack(spacing: KccSpacing.s4) {
                membershipCard
                planCard(
                    productID: plusMonthlyProductID,
                    title: "subscription.plusPlanName",
                    body: "subscription.plusBody"
                )
                planCard(
                    productID: supporterMonthlyProductID,
                    title: "subscription.supporterPlanName",
                    body: "subscription.supporterBody"
                )

                Text("iosStore.renewal", tableName: "AppStore").font(.footnote).foregroundStyle(KccTheme.secondary)
                AppStoreLegalLinks()
                statusView

                Button { Task { await coordinator.restore() } } label: {
                    Text("subscription.restoreAction").frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered)
                .disabled(coordinator.status.isBusy)

                if access.activeMember || coordinator.currentTier != nil {
                    Button {
                        showingSubscriptions = true
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
        .refreshable { await coordinator.reloadAndReconcile() }
        .manageSubscriptionsSheet(isPresented: $showingSubscriptions)
        .onChange(of: showingSubscriptions) { _, presented in
            if !presented { Task { await coordinator.reloadAndReconcile() } }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await coordinator.reloadAndReconcile() } }
        }
        .onChange(of: access.activeMember) { _, _ in
            Task { await coordinator.reloadAndReconcile() }
        }
    }

    private var membershipCard: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("subscription.currentPlanTitle").font(.headline)
            Text(currentEntitlementKey)
                .foregroundStyle(KccTheme.secondary)
            if let change = coordinator.upcomingChange {
                Text(String.localizedStringWithFormat(
                    NSLocalizedString("subscription.scheduledPlusBody", comment: ""),
                    change.effectiveAt.formatted(.dateTime.year().month(.abbreviated).day().hour().minute().locale(locale))))
                    .font(.footnote)
                    .accessibilityIdentifier("membership.scheduledChange")
            }
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
                Text(String.localizedStringWithFormat(NSLocalizedString("iosStore.monthlyPrice", tableName: "AppStore", comment: "Store price and verified monthly duration"), product.displayPrice))
                    .font(.subheadline.weight(.semibold))
            } else if coordinator.status == .loading {
                ProgressView()
            } else {
                Text("subscription.priceUnavailable").font(.footnote)
            }
            Button { Task { await coordinator.purchase(productID) } } label: {
                EditorSubmitLabel(title: isCurrent ? "subscription.currentPlanAction" : actionKey(for: productID))
            }
            .kccPrimaryAction()
            .disabled(isCurrent || coordinator.isChangeScheduled(to: productID)
                || !coordinator.canPurchase || access.isRestricted)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }

    private func actionKey(for productID: String) -> LocalizedStringKey {
        if coordinator.isChangeScheduled(to: productID) {
            return "subscription.planChangeScheduledAction"
        }
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
        if let key = coordinator.status.messageKey {
            Text(LocalizedStringKey(key))
                .font(.footnote)
                .foregroundStyle(isFailure ? Color.red : Color.secondary)
                .multilineTextAlignment(.center)
        }
        if coordinator.catalogueUnavailable {
            Text("subscription.catalogueRefreshFailed").font(.footnote)
                .foregroundStyle(KccTheme.secondary)
        }
        if coordinator.renewalInformationUnavailable {
            Text("subscription.renewalRefreshFailed").font(.footnote)
                .foregroundStyle(KccTheme.secondary)
        }
        if isFailure || coordinator.catalogueUnavailable || coordinator.renewalInformationUnavailable {
            Button { Task { await coordinator.reloadAndReconcile() } } label: { Text("notifications.retry").frame(minHeight: 48) }
            .buttonStyle(.bordered)
            .disabled(coordinator.status.isBusy)
        }
    }

    private var isFailure: Bool {
        if case .failed = coordinator.status { return true }
        return false
    }
}
