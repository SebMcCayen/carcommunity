import SwiftUI

/// The Crown-Hunt hub — the map-independent read surfaces gathered under one
/// tabbed container, the iOS analog of Android's `CrownHuntScreen` (Home + Shop
/// tabs), plus navigation to the claim-history and season-standings screens.
///
/// A dumb container: it holds already-built coordinators and the resolved
/// ``CrownHuntFlags`` (from ``CrownHuntComposition``), and only DECIDES which
/// tabs to offer. The Shop tab is present ONLY when
/// ``PerkShopCoordinator/isShopEnabled`` (the `crownHuntPerks` flag on, a
/// repository, a uid and the member gate) — mirroring Android rendering the
/// whole shop tab dark behind the contract-default-OFF flag. Exported ready for
/// the shell-wiring PR (`ShellRoute.crownHunt`); not yet referenced by the
/// shell, which this slice deliberately does not modify.
struct CrownHuntHubView: View {
    let statsCoordinator: CrownHuntStatsCoordinator
    let claimsCoordinator: CrownHuntClaimsCoordinator
    let shopCoordinator: PerkShopCoordinator
    /// The top-level `crownHunt` flag (``CrownHuntFlags/crownHuntEnabled``) —
    /// "Off hides Kronjakt entirely" per contracts/features/feature-flags.json.
    /// While false the hub renders only the disabled placeholder, so an
    /// operator switching the whole feature off actually hides it rather than
    /// leaving the tabs reachable.
    let crownHuntEnabled: Bool

    var body: some View {
        if crownHuntEnabled {
            TabView {
                NavigationStack {
                    CrownHuntHomeScreen(coordinator: statsCoordinator)
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) {
                                NavigationLink {
                                    ClaimHistoryScreen(coordinator: claimsCoordinator)
                                } label: {
                                    Image(systemName: "clock.arrow.circlepath")
                                        .accessibilityLabel(ClaimHistoryScreen.title)
                                }
                            }
                        }
                }
                .tabItem { Label("crownHunt.tabHome", systemImage: "crown") }

                if shopCoordinator.isShopEnabled {
                    NavigationStack {
                        PerkShopScreen(coordinator: shopCoordinator)
                    }
                    .tabItem { Label("crownHunt.tabShop", systemImage: "bag") }
                }
            }
        } else {
            CrownHuntMessageState(
                title: "crownHunt.screenTitle", message: "crownHunt.resultFeatureDisabled"
            )
        }
    }
}
