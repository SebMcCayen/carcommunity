import Foundation

/// The Crown-Hunt feature's own composition root — builds the coordinators for
/// the four read surfaces from the guarded Firebase repositories and the
/// resolved feature flags, so the later shell-wiring PR has a SINGLE entry
/// point (the shell already has a `ShellRoute.crownHunt` case).
///
/// Every repository is `createIfAvailable()` (nil in a config-less build), and
/// the flags are read once and folded onto the contract defaults — so a build
/// without Firebase, or with the `crownHuntPerks` flag off, yields a hub whose
/// shop is simply ``PerkShopUiState/unavailable`` and never rendered, exactly as
/// Android gates it.
@MainActor
struct CrownHuntComposition {
    let statsCoordinator: CrownHuntStatsCoordinator
    let claimsCoordinator: CrownHuntClaimsCoordinator
    let shopCoordinator: PerkShopCoordinator
    let flags: CrownHuntFlags

    /// Builds the live composition for `uid`. Reads the feature flags first
    /// (falling back to contract defaults on any failure) so the shop
    /// coordinator's gate matches the backend, then wires every coordinator to
    /// its guarded repository.
    ///
    /// - Parameter passesMemberGate: while member gating is disabled repo-wide
    ///   this is true for any signed-in, non-suspended user (the current launch
    ///   posture) — the iOS mirror of Android's `passesMemberGate`.
    static func live(uid: String?, passesMemberGate: Bool) async -> CrownHuntComposition {
        let flags = await FirebaseCrownHuntFeatureFlagsRepository.createIfAvailable()?.flags()
            ?? .contractDefaults
        return CrownHuntComposition(
            statsCoordinator: CrownHuntStatsCoordinator(
                repository: FirebaseCrownHuntStatsRepository.createIfAvailable(),
                uid: uid,
                passesMemberGate: passesMemberGate
            ),
            claimsCoordinator: CrownHuntClaimsCoordinator(
                repository: FirebaseCrownHuntClaimsRepository.createIfAvailable(),
                uid: uid,
                passesMemberGate: passesMemberGate
            ),
            shopCoordinator: PerkShopCoordinator(
                repository: FirebasePerkShopRepository.createIfAvailable(),
                balanceRepository: FirebasePerkBalanceRepository.createIfAvailable(),
                uid: uid,
                perksEnabled: flags.perksEnabled,
                passesMemberGate: passesMemberGate
            ),
            flags: flags
        )
    }
}
