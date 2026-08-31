import SwiftUI

/// Shared SwiftUI building blocks for the Crown-Hunt screens: a card container,
/// a centered message state (loading / empty / error / unavailable), and the
/// localized-key mappers for claim results and rarities. Kept in one place so
/// the four screens render consistently and every user-facing string resolves
/// against the generated `Localizable.xcstrings` (contracts/localization).

/// A rounded surface card matching the app's token set (``KccRadius`` /
/// ``KccSpacing``), used for every stats / standings / shop / claim card.
struct CrownHuntCard<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(KccSpacing.s4)
        .background(
            RoundedRectangle(cornerRadius: KccRadius.md)
                .fill(Color(.secondarySystemBackground))
        )
    }
}

/// A centered title + body message, for the loading / empty / unavailable /
/// error states — the iOS analog of the events feature's `messageState`.
struct CrownHuntMessageState: View {
    let title: LocalizedStringKey
    let message: LocalizedStringKey
    /// An optional retry affordance (shown only for the failed state).
    var retry: (() -> Void)?

    var body: some View {
        VStack(spacing: KccSpacing.s3) {
            Text(title)
                .font(.headline)
                .multilineTextAlignment(.center)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let retry {
                Button("crownHunt.retry", action: retry)
                    .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(KccSpacing.s6)
    }
}

enum CrownHuntStrings {
    /// The localized message key for a claim result — reuses the existing
    /// `crownHunt.result*` vocabulary (the same strings the collect flow shows).
    static func resultKey(_ result: CrownHuntClaimResult) -> LocalizedStringKey {
        switch result {
        case .awarded: return "crownHunt.resultAwarded"
        case .alreadyClaimed: return "crownHunt.resultAlreadyClaimed"
        case .outsideGeofence: return "crownHunt.resultOutsideGeofence"
        case .movingTooFast: return "crownHunt.resultMovingTooFast"
        case .positionTooOld: return "crownHunt.resultPositionTooOld"
        case .pointInactive: return "crownHunt.resultPointInactive"
        case .cooldownActive: return "crownHunt.resultCooldownActive"
        case .dailyLimitReached: return "crownHunt.resultDailyLimit"
        case .riskReview: return "crownHunt.resultRiskReview"
        case .featureDisabled: return "crownHunt.resultFeatureDisabled"
        case .notEligible: return "crownHunt.resultNotEligible"
        }
    }

    /// The localized rarity name key (for a `Text`).
    static func rarityKey(_ rarity: CrownRarity) -> LocalizedStringKey {
        switch rarity {
        case .common: return "crownHunt.rarityCommon"
        case .uncommon: return "crownHunt.rarityUncommon"
        case .rare: return "crownHunt.rarityRare"
        case .legendary: return "crownHunt.rarityLegendary"
        }
    }

    /// The localized rarity name resolved to a `String` (for a value slot).
    static func rarityName(_ rarity: CrownRarity) -> String {
        switch rarity {
        case .common: return String(localized: "crownHunt.rarityCommon")
        case .uncommon: return String(localized: "crownHunt.rarityUncommon")
        case .rare: return String(localized: "crownHunt.rarityRare")
        case .legendary: return String(localized: "crownHunt.rarityLegendary")
        }
    }

    /// The localized message key for a buy-failure reason.
    static func buyFailureKey(_ reason: PerkBuyFailureReason) -> LocalizedStringKey {
        switch reason {
        case .insufficientFunds: return "crownHunt.shopErrorInsufficient"
        case .holdCap: return "crownHunt.shopErrorHoldCap"
        case .unavailable: return "crownHunt.shopErrorUnavailable"
        case .unknown: return "crownHunt.shopErrorUnknown"
        }
    }

    // MARK: - Formatted (positional-argument) strings
    //
    // The String Catalog stores positional printf specifiers (`%1$lld`,
    // `%1$@`), so a value with an argument is rendered with
    // `localizedStringWithFormat` — the same pattern the events feature uses
    // for `events.rowGoingCount`.

    /// "%1$lld CP" (crownHunt.kpValue) with a Crown-Point amount.
    static func kpValue(_ points: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("crownHunt.kpValue", comment: "Crown Points amount"), points
        )
    }

    /// "#%1$lld" (crownHunt.rankValue) with a leaderboard rank.
    static func rankValue(_ rank: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("crownHunt.rankValue", comment: "Leaderboard rank"), rank
        )
    }

    /// "You own: %1$lld" (crownHunt.shopOwnedLabel) with an owned count.
    static func ownedLabel(_ count: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("crownHunt.shopOwnedLabel", comment: "Owned perk count"), count
        )
    }

    /// "Bought! %1$@ is now in your inventory." (crownHunt.shopBoughtMessage).
    static func boughtMessage(_ perkName: String) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("crownHunt.shopBoughtMessage", comment: "Purchase confirmation"),
            perkName
        )
    }

    /// "Lasts %1$lld hour(s)" — singular/plural are two separate keys selected
    /// in code (crownHunt.shopDurationLabelOne / …Other), mirroring Android,
    /// because the generated catalog emits plain strings, not `.stringsdict`.
    static func durationLabel(hours: Int) -> String {
        let key = hours == 1 ? "crownHunt.shopDurationLabelOne" : "crownHunt.shopDurationLabelOther"
        return String.localizedStringWithFormat(
            NSLocalizedString(key, comment: "Perk effect duration"), hours
        )
    }
}
