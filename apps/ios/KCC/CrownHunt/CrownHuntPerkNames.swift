import Foundation
import SwiftUI

/// Bilingual display resolution for Kronjakt perks — the iOS port of Android's
/// `PerkNames.kt`.
///
/// Every perk has BOTH a Swedish and an English name, wired two ways so the
/// shop always renders in the member's chosen app language:
///  - the CATALOG mirror carries both `name` (sv) and `nameEn` (en), reaching
///    the client as ``PerkCatalogEntry/name`` / ``PerkCatalogEntry/nameEn``;
///  - the localization catalog carries a per-perk `crownHunt.perkName…` string
///    which the framework resolves in the SAME language as the rest of the UI,
///    offline and instantly.
///
/// Resolution prefers the localized string for the three known perks (the
/// authoritative, offline display); an unknown/future perk falls back to the
/// catalog's own bilingual pair, picking `nameEn` when the app is showing
/// English and `name` (sv) otherwise. All resolved to a plain `String` so a
/// card can show either a localized-key name or a catalog-supplied one through
/// one `Text`.
enum CrownHuntPerkNames {
    /// The bilingual display name for a perk.
    static func displayName(perkId: String, nameSv: String, nameEn: String) -> String {
        if let key = nameKey(perkId) {
            return String(localized: key)
        }
        if isEnglish, !nameEn.isEmpty { return nameEn }
        return nameSv
    }

    /// The localized description (blurb) for a perk. The catalog mirror carries
    /// only the Swedish blurb, so for the three known perks this resolves the
    /// localized per-perk string (rendered in the app's language) and falls back
    /// to the catalog's Swedish blurb for any unknown/future perk.
    static func blurb(perkId: String, blurbSv: String) -> String {
        if let key = blurbKey(perkId) {
            return String(localized: key)
        }
        return blurbSv
    }

    /// The localized family label for a perk kind (`Trap` / `Shield` / `Boost`).
    static func kindLabelKey(_ kind: PerkKind) -> LocalizedStringKey {
        switch kind {
        case .trap: return "crownHunt.perkKindTrap"
        case .shield: return "crownHunt.perkKindShield"
        case .boost: return "crownHunt.perkKindBoost"
        }
    }

    /// Display-only effect duration per perk kind, in whole hours, for the
    /// shop's "how long it lasts" label. MIRRORS the authoritative server
    /// constants (functions `crownHunt/perks-core.ts`: trap 6 h, shield 3 h,
    /// boost 1 h), which are deliberately NOT sent to the client in the catalog
    /// mirror. Keep in sync if the server durations ever change.
    static func durationHours(_ kind: PerkKind) -> Int {
        switch kind {
        case .trap: return 6
        case .shield: return 3
        case .boost: return 1
        }
    }

    private static var isEnglish: Bool {
        Locale.current.language.languageCode?.identifier == "en"
    }

    private static func nameKey(_ perkId: String) -> String.LocalizationValue? {
        switch perkId {
        case "spike_strip": return "crownHunt.perkNameSpikeStrip"
        case "shield": return "crownHunt.perkNameShield"
        case "boost": return "crownHunt.perkNameBoost"
        default: return nil
        }
    }

    private static func blurbKey(_ perkId: String) -> String.LocalizationValue? {
        switch perkId {
        case "spike_strip": return "crownHunt.perkBlurbSpikeStrip"
        case "shield": return "crownHunt.perkBlurbShield"
        case "boost": return "crownHunt.perkBlurbBoost"
        default: return nil
        }
    }
}
