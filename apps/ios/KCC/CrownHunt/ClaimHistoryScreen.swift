import SwiftUI

/// The member's own Crown-Hunt claim history — a read of their `crownHuntClaims`
/// (owner-only). A net-new iOS surface with no Android counterpart (the Android
/// hub does not list claim history), built on the shared contract shape.
///
/// A dumb switch over ``CrownHuntClaimsUiState``; every decision lives in the
/// pure ``CrownHuntClaimsCoordinator``. Nil coordinator = config-less build →
/// the unavailable placeholder.
///
struct ClaimHistoryScreen: View {
    let coordinator: CrownHuntClaimsCoordinator?

    var body: some View {
        ScrollView {
            // A LazyVStack, not a VStack: the list is bounded to
            // ``CrownHuntClaims/queryLimit`` (100) rows, and a plain VStack
            // would eagerly lay every row out even when the member has never
            // scrolled past the first few.
            LazyVStack(spacing: KccSpacing.s3) {
                content
            }
            .padding(KccSpacing.s4)
        }
        .kccReadingSurface(pageBackground: true)
        .accessibilityIdentifier("crown.history")
        .navigationTitle(Text(verbatim: Self.title))
        .task { coordinator?.start() }
    }

    static var title: String { String(localized: "crownHunt.claimHistoryTitle") }
    private static var emptyBody: String { String(localized: "crownHunt.claimHistoryEmpty") }
    private static var errorBody: String { String(localized: "crownHunt.claimHistoryError") }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            switch coordinator.state {
            case .loading:
                CrownHuntMessageState(title: "crownHunt.screenTitle", message: "crownHunt.loading")
            case .empty:
                VStack(spacing: KccSpacing.s3) {
                    Text(verbatim: Self.title)
                        .font(.headline)
                    Text(verbatim: Self.emptyBody)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(KccSpacing.s6)
            case .loaded(let claims):
                ForEach(claims) { claim in
                    CrownClaimRow(claim: claim)
                }
            case .unavailable:
                CrownHuntMessageState(
                    title: "crownHunt.screenTitle", message: "crownHunt.resultFeatureDisabled"
                )
            case .failed:
                VStack(spacing: KccSpacing.s3) {
                    Text(verbatim: Self.title)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                    Text(verbatim: Self.errorBody)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Button("crownHunt.retry", action: { coordinator.reload() })
                        .buttonStyle(.bordered)
                }
                .frame(maxWidth: .infinity)
                .padding(KccSpacing.s6)
            }
        } else {
            CrownHuntMessageState(
                title: "crownHunt.screenTitle", message: "crownHunt.resultFeatureDisabled"
            )
        }
    }
}

/// One claim attempt: its localized outcome, when it happened, and — for an
/// awarded claim — the Crown Points earned.
struct CrownClaimRow: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let claim: CrownHuntClaim

    var body: some View {
        CrownHuntCard {
            let layout = dynamicTypeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: KccSpacing.s2))
                : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: KccSpacing.s3))
            layout {
                Text(CrownHuntStrings.resultKey(claim.result))
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .foregroundStyle(KccTheme.text)
                if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: KccSpacing.s2) }
                if claim.result == .awarded, let points = claim.pointsAwarded {
                    Text(CrownHuntStrings.kpValue(points))
                        .font(.subheadline)
                        .fontWeight(.semibold)
                }
            }
            if let claimedAt = claim.claimedAt {
                Text(claimedAt.formatted(date: .abbreviated, time: .shortened))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

#Preview("Claims – loaded") {
    NavigationStack {
        ScrollView {
            VStack(spacing: KccSpacing.s3) {
                CrownClaimRow(
                    claim: CrownHuntClaim(
                        id: "1", pointId: "p1", result: .awarded,
                        claimedAt: Date(), pointsAwarded: 25
                    )
                )
                CrownClaimRow(
                    claim: CrownHuntClaim(
                        id: "2", pointId: "p2", result: .movingTooFast,
                        claimedAt: Date(), pointsAwarded: nil
                    )
                )
            }
            .padding()
        }
    }
}
