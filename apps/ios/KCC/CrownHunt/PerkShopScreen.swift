import SwiftUI

/// The Kronjakt SHOP — Crown-Point balance, the buyable perk catalog (each with
/// its owned count + a Buy button), and the last-buy result banner. The iOS port
/// of Android's `PerkShopContent` / `PerkCard` / `BuyStatusBanner`.
///
/// GATED, exactly like Android: the shop is wired only when the
/// contract-default-OFF `crownHuntPerks` flag is on (plus a repository, uid and
/// the member gate). While off, ``PerkShopCoordinator/isShopEnabled`` is false,
/// nothing subscribes, and the state is ``PerkShopUiState/unavailable`` — the
/// whole tab ships dark. A host offers this tab only when `isShopEnabled`.
struct PerkShopScreen: View {
    let coordinator: PerkShopCoordinator?

    var body: some View {
        ScrollView {
            VStack(spacing: KccSpacing.s4) {
                content
            }
            .padding(KccSpacing.s4)
        }
        .navigationTitle(Text("crownHunt.tabShop"))
        .task { coordinator?.start() }
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            switch coordinator.state {
            case .loading:
                CrownHuntMessageState(title: "crownHunt.tabShop", message: "crownHunt.shopLoading")
            case .empty:
                CrownHuntMessageState(title: "crownHunt.tabShop", message: "crownHunt.shopEmpty")
            case .loaded(let balanceKp, let items):
                loaded(coordinator: coordinator, balanceKp: balanceKp, items: items)
            case .unavailable:
                CrownHuntMessageState(
                    title: "crownHunt.tabShop", message: "crownHunt.resultFeatureDisabled"
                )
            case .failed:
                CrownHuntMessageState(
                    title: "crownHunt.tabShop",
                    message: "crownHunt.shopError",
                    retry: { coordinator.reload() }
                )
            }
        } else {
            CrownHuntMessageState(
                title: "crownHunt.tabShop", message: "crownHunt.resultFeatureDisabled"
            )
        }
    }

    @ViewBuilder
    private func loaded(
        coordinator: PerkShopCoordinator, balanceKp: Int, items: [PerkShopItem]
    ) -> some View {
        BalanceCard(balanceKp: balanceKp)
        Text("crownHunt.shopIntro")
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
        BuyStatusBanner(status: coordinator.buyStatus, items: items)
        ForEach(items) { item in
            PerkCard(item: item, buyStatus: coordinator.buyStatus) { perk in
                Task { await coordinator.buy(perkId: perk.entry.perkId, affordable: perk.affordable) }
            }
        }
    }
}

/// The member's current Crown-Point balance — Android's `PerkBalanceCard`.
private struct BalanceCard: View {
    let balanceKp: Int

    var body: some View {
        CrownHuntCard {
            HStack {
                Text("crownHunt.shopBalanceLabel")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(CrownHuntStrings.kpValue(balanceKp))
                    .font(.title3)
                    .fontWeight(.bold)
            }
        }
    }
}

/// A single perk row: name + family, blurb, effect duration, owned count, cost
/// and the Buy button — Android's `PerkCard`. The button spins and disables
/// while ITS buy is in flight, and is disabled for every row while ANY buy is
/// in flight (the coordinator's in-flight guard backs this up server-safely).
private struct PerkCard: View {
    let item: PerkShopItem
    let buyStatus: PerkBuyStatus
    let onBuy: (PerkShopItem) -> Void

    private var buyingThis: Bool {
        if case .buying(let perkId) = buyStatus { return perkId == item.entry.perkId }
        return false
    }

    private var anyBuying: Bool {
        if case .buying = buyStatus { return true }
        return false
    }

    var body: some View {
        CrownHuntCard {
            HStack(alignment: .firstTextBaseline) {
                Text(
                    CrownHuntPerkNames.displayName(
                        perkId: item.entry.perkId,
                        nameSv: item.entry.name,
                        nameEn: item.entry.nameEn
                    )
                )
                .font(.headline)
                .frame(maxWidth: .infinity, alignment: .leading)
                Text(CrownHuntPerkNames.kindLabelKey(item.entry.kind))
                    .font(.caption)
                    .foregroundStyle(KccPalette.crownGold)
            }
            Text(CrownHuntPerkNames.blurb(perkId: item.entry.perkId, blurbSv: item.entry.blurb))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text(verbatim: CrownHuntStrings.durationLabel(hours: CrownHuntPerkNames.durationHours(item.entry.kind)))
                .font(.caption)
                .foregroundStyle(KccPalette.crownGold)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: KccSpacing.s1) {
                    Text(CrownHuntStrings.kpValue(item.entry.costKp))
                        .font(.headline)
                    Text(verbatim: CrownHuntStrings.ownedLabel(item.ownedCount))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    onBuy(item)
                } label: {
                    if buyingThis {
                        HStack(spacing: KccSpacing.s2) {
                            ProgressView()
                            Text("crownHunt.shopBuying")
                        }
                    } else {
                        Text("crownHunt.shopBuyButton")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(anyBuying || !item.affordable)
            }
        }
    }
}

/// The result banner for the most recent buy — Android's `BuyStatusBanner`. A
/// success line (or "already bought" on an idempotent replay), or the
/// reason-specific error. Idle/Buying render nothing.
private struct BuyStatusBanner: View {
    let status: PerkBuyStatus
    let items: [PerkShopItem]

    var body: some View {
        switch status {
        case .idle, .buying:
            EmptyView()
        case .bought(let perkId, _, _, let alreadyPurchased):
            Text(verbatim: boughtMessage(perkId: perkId, alreadyPurchased: alreadyPurchased))
                .font(.subheadline)
                .foregroundStyle(KccPalette.successGreen)
                .frame(maxWidth: .infinity, alignment: .leading)
        case .failed(_, let reason):
            Text(CrownHuntStrings.buyFailureKey(reason))
                .font(.subheadline)
                .foregroundStyle(KccPalette.errorRed)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func boughtMessage(perkId: String, alreadyPurchased: Bool) -> String {
        if alreadyPurchased {
            return String(localized: "crownHunt.shopAlreadyBoughtMessage")
        }
        let entry = items.first(where: { $0.entry.perkId == perkId })?.entry
        let name = entry.map {
            CrownHuntPerkNames.displayName(perkId: $0.perkId, nameSv: $0.name, nameEn: $0.nameEn)
        } ?? perkId
        return CrownHuntStrings.boughtMessage(name)
    }
}

#Preview("Shop – loaded") {
    NavigationStack {
        ScrollView {
            VStack(spacing: KccSpacing.s4) {
                BalanceCard(balanceKp: 240)
                PerkCard(
                    item: PerkShopItem(
                        entry: PerkCatalogEntry(
                            perkId: "spike_strip", kind: .trap, name: "Spikmatta",
                            iconKey: "trap", costKp: 150, blurb: "…", nameEn: "Spike strip"
                        ),
                        ownedCount: 1, affordable: true
                    ),
                    buyStatus: .idle,
                    onBuy: { _ in }
                )
            }
            .padding()
        }
    }
}
