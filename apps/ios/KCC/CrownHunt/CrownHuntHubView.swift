import SwiftUI

enum CrownHubTab: String {
    case overview, statistics, shop

    var title: LocalizedStringKey {
        switch self {
        case .overview: "crownHunt.overviewTab"
        case .statistics: "crownHunt.statisticsTab"
        case .shop: "crownHunt.tabShop"
        }
    }
}

/// One native stack and a pinned, scrollable tab strip. Shop availability is
/// still decided by the existing coordinator, never by presentation state.
struct CrownHuntHubView: View {
    let statsCoordinator: CrownHuntStatsCoordinator
    let claimsCoordinator: CrownHuntClaimsCoordinator
    let shopCoordinator: PerkShopCoordinator
    let crownHuntEnabled: Bool
    let onBack: () -> Void
    @State private var selectedTab = CrownHubTab.overview

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if crownHuntEnabled {
                    CrownHubTabs(selection: $selectedTab, shopEnabled: shopCoordinator.isShopEnabled)
                    tabContent
                } else {
                    ScrollView {
                        CrownHuntMessageState(title: "crownHunt.screenTitle", message: "crownHunt.resultFeatureDisabled")
                    }
                }
            }
            .kccReadingSurface(pageBackground: true)
            .navigationTitle("crownHunt.screenTitle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onBack) {
                        Label("shell.back", systemImage: "chevron.backward")
                            .frame(minHeight: 48).contentShape(Rectangle())
                    }
                    .accessibilityIdentifier("crown.back")
                }
                if crownHuntEnabled {
                    ToolbarItem(placement: .topBarTrailing) {
                        NavigationLink {
                            ClaimHistoryScreen(coordinator: claimsCoordinator)
                        } label: {
                            Image(systemName: "clock.arrow.circlepath")
                                .frame(width: 48, height: 48).contentShape(Rectangle())
                        }
                        .accessibilityLabel(ClaimHistoryScreen.title)
                        .accessibilityIdentifier("crown.history.open")
                    }
                }
            }
        }
        // Rewiring identity or disabling Crown Hunt must also remove pushed
        // history/instruction routes, not leave an old destination on screen.
        .id(CrownHubIdentity(stats: ObjectIdentifier(statsCoordinator), enabled: crownHuntEnabled))
        .onChange(of: shopCoordinator.isShopEnabled) { _, enabled in
            if !enabled, selectedTab == .shop { selectedTab = .overview }
        }
    }

    @ViewBuilder private var tabContent: some View {
        switch selectedTab {
        case .overview: CrownHuntHomeScreen(coordinator: statsCoordinator)
        case .statistics: CrownHuntHomeScreen(coordinator: statsCoordinator, showsFullStatistics: true)
        case .shop:
            if shopCoordinator.isShopEnabled { PerkShopScreen(coordinator: shopCoordinator) }
        }
    }
}

struct CrownHubTabs: View {
    @Binding var selection: CrownHubTab
    let shopEnabled: Bool
    private var tabs: [CrownHubTab] { shopEnabled ? [.overview, .statistics, .shop] : [.overview, .statistics] }

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: KccSpacing.s2) {
                ForEach(tabs, id: \.rawValue) { tab in tabButton(tab) }
            }
            .padding(.horizontal, KccSpacing.s4)
            .padding(.vertical, KccSpacing.s2)
        }
        .scrollIndicators(.hidden)
        .accessibilityIdentifier("crown.tabs")
    }

    private func tabButton(_ tab: CrownHubTab) -> some View {
        Button { selection = tab } label: {
            Text(tab.title).font(.subheadline.weight(.semibold))
                .lineLimit(1).fixedSize(horizontal: true, vertical: true)
                .padding(.horizontal, KccSpacing.s4)
                .padding(.vertical, KccSpacing.s2)
                .frame(minHeight: 48).contentShape(Rectangle())
                .foregroundStyle(selection == tab ? KccTheme.onGold : KccTheme.text)
                .background(selection == tab ? KccPalette.crownGold : KccTheme.surface, in: Capsule())
                .overlay { Capsule().strokeBorder(KccTheme.border).allowsHitTesting(false) }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selection == tab ? .isSelected : [])
        .accessibilityIdentifier("crown.tab.\(tab.rawValue)")
    }
}

struct CrownInstructionsScreen: View {
    private let sections = ["Intro", "Collect", "Rarities", "Shared", "Expire", "Spawn", "Points", "Rank"]
    var body: some View {
        ScrollView {
            LazyVStack(spacing: KccSpacing.s4) {
                ForEach(sections, id: \.self) { section in
                    CrownHuntCard {
                        Text(LocalizedStringKey("crownHunt.instr\(section)Title")).font(.headline)
                        Text(LocalizedStringKey(section == "Rarities" ? "crownHunt.instrRaritiesIntro" : "crownHunt.instr\(section)Body"))
                            .font(.body).foregroundStyle(KccTheme.secondary)
                    }
                }
            }.padding(KccSpacing.s4)
        }
        .kccReadingSurface(pageBackground: true)
        .navigationTitle("crownHunt.instrTitle")
        .accessibilityIdentifier("crown.instructions")
    }
}

private struct CrownHubIdentity: Hashable {
    let stats: ObjectIdentifier
    let enabled: Bool
}
