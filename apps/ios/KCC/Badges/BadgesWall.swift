import SwiftUI

/// The member's own BADGE WALL — every rung of every ladder, earned lit and
/// unearned greyed, with unlock dates and the climb to the next rung, plus the
/// standalone milestones the member holds. The iOS port of Android's
/// own-profile badge wall (`badges/BadgeWall.kt` +
/// `profile/ProfileBadgesSection.kt`), including its milestones behaviour:
/// unlike a ladder rung, an unheld milestone is never rendered as a locked
/// goal — several (`early_tester`, the per-season podium badges) are granted
/// by an admin or by a one-off event rather than earned by climbing, so there
/// is nothing actionable to show as "locked".
///
/// EXPORTED, NOT WIRED. Android hosts its wall INSIDE the profile
/// (`ProfileBadgesSection`); on iOS this slice ships the wall as a reusable,
/// self-contained screen so the profile can host it later without this feature
/// reaching into `KCC/Profile`. It builds its own coordinator from the
/// feature-level factory (argument-free, like ``GaragePanel``), and also
/// accepts an injected coordinator for previews and tests.
///
/// Presentational only beyond the coordinator it drives: it renders the
/// pre-folded ``BadgeShowcase`` and reads no backend itself.
struct BadgesWall: View {
    @State private var coordinator: BadgesCoordinator

    /// Production wiring: builds the coordinator from the feature-level factory.
    /// In a config-less build the factory returns nil and the coordinator
    /// settles on ``BadgesUiState/unavailable``. The uid is resolved by the
    /// coordinator from the repository's own session seam.
    init() {
        self.init(coordinator: BadgesCoordinator(repository: FirebaseBadgesRepository.createIfAvailable()))
    }

    /// Preview/test seam: inject a coordinator (typically fed by a fake
    /// repository).
    init(coordinator: BadgesCoordinator) {
        _coordinator = State(initialValue: coordinator)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s5) {
                Text("badges.screenTitle")
                    .font(.system(size: KccTypeScale.headingLg, weight: KccTypeScale.semibold))

                content
            }
            .padding(KccSpacing.s6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .task { coordinator.start() }
    }

    @ViewBuilder
    private var content: some View {
        switch coordinator.state {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, KccSpacing.s8)
        case .unavailable:
            Text("memberProfile.badgesUnavailable")
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(.secondary)
        case .failed:
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                Text("memberProfile.badgesLoadError")
                    .font(.system(size: KccTypeScale.bodyMd))
                    .foregroundStyle(.secondary)
                Button { coordinator.reload() } label: {
                    Text("badges.retry")
                }
                .buttonStyle(.bordered)
            }
        case .empty(let showcase), .loaded(let showcase):
            BadgeWallContent(showcase: showcase)
        }
    }
}

/// The wall itself, given a folded showcase — shared by the earned and
/// zero-earned states (the zero-earned wall is the same catalog rendered as a
/// menu of goals, so the only difference is the header copy).
private struct BadgeWallContent: View {
    let showcase: BadgeShowcase

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s5) {
            header

            let unfinished = showcase.laddersInProgress
            if !unfinished.isEmpty {
                Divider()
                Text("badgeShowcase.progressTitle")
                    .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.semibold))
                ForEach(unfinished) { progress in
                    LadderProgressRow(progress: progress)
                }
            }

            Divider()
            AllAwardsGrid(showcase: showcase)
        }
    }

    @ViewBuilder
    private var header: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text(BadgeText.subtitle(earned: showcase.earnedCount, total: showcase.totalCount))
                .font(.system(size: KccTypeScale.bodySm))
                .foregroundStyle(.secondary)

            if !showcase.hasAnyBadge {
                Text("badgeShowcase.emptyTitle")
                    .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
                Text("badgeShowcase.emptyBody")
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Progress rows

/// The climb: target rung, what it takes, and a bar when the counter is
/// knowable — Android's `LadderProgressRow`.
private struct LadderProgressRow: View {
    let progress: LadderProgress

    var body: some View {
        let ladder = progress.ladder
        if let next = progress.nextRung {
            let threshold = formatLadderValue(unit: ladder.unit, value: next.threshold)
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                HStack {
                    Text(LocalizedStringKey(BadgeStrings.ladderNameKey(ladder.id)))
                        .font(.system(size: KccTypeScale.bodyMd))
                    Spacer()
                    Text(BadgeText.nextTier(tierName: tierName(next.tier)))
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(.secondary)
                }
                Text(BadgeText.ladderRequirement(ladder.id, threshold: threshold))
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(.secondary)

                if let fraction = progress.fractionToNext, let observed = progress.observedValue {
                    ProgressView(value: fraction)
                        .tint(KccPalette.crownGold)
                    Text(BadgeText.progressCount(
                        current: formatLadderValue(unit: ladder.unit, value: observed),
                        target: threshold
                    ))
                    .font(.system(size: KccTypeScale.caption))
                    .foregroundStyle(.secondary)
                } else {
                    // No honest client-readable counter yet — say so rather
                    // than draw a bar the app cannot back up.
                    Text(LocalizedStringKey(BadgeStrings.ladderTaglineKey(ladder.id)))
                        .font(.system(size: KccTypeScale.caption))
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func tierName(_ tier: BadgeTier) -> String {
        String(localized: String.LocalizationValue(BadgeStrings.tierNameKey(tier)))
    }
}

// MARK: - Full wall grid

/// Every rung of every ladder plus the standalone milestones — Android's
/// `AllAwardsGrid`. Earned rungs are lit with their tier colour and carry
/// their unlock date; unearned rungs are greyed.
private struct AllAwardsGrid: View {
    let showcase: BadgeShowcase

    private let columns = [GridItem(.adaptive(minimum: 78), spacing: KccSpacing.s3)]

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s5) {
            ForEach(showcase.ladders) { progress in
                let earnedKeys = Set(progress.earnedRungs.map(\.badgeKey))
                VStack(alignment: .leading, spacing: KccSpacing.s3) {
                    Text(LocalizedStringKey(BadgeStrings.ladderNameKey(progress.ladder.id)))
                        .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
                    LazyVGrid(columns: columns, alignment: .leading, spacing: KccSpacing.s3) {
                        ForEach(progress.ladder.rungs, id: \.badgeKey) { rung in
                            let earned = earnedKeys.contains(rung.badgeKey)
                            BadgeMedallionTile(
                                tier: rung.tier,
                                earned: earned,
                                label: tierName(rung.tier),
                                ladderName: ladderName(progress.ladder.id),
                                awardedAt: earned ? showcase.awardedAtByKey[rung.badgeKey] : nil
                            )
                        }
                    }
                }
            }

            if !showcase.milestones.isEmpty {
                VStack(alignment: .leading, spacing: KccSpacing.s3) {
                    Text("badgeShowcase.milestonesTitle")
                        .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
                    LazyVGrid(columns: columns, alignment: .leading, spacing: KccSpacing.s3) {
                        ForEach(showcase.milestones) { milestone in
                            BadgeMedallionTile(
                                tier: nil,
                                earned: true,
                                label: milestoneName(milestone),
                                ladderName: milestoneName(milestone),
                                awardedAt: milestone.awardedAt
                            )
                        }
                    }
                }
            }
        }
    }

    private func ladderName(_ id: BadgeLadderId) -> String {
        String(localized: String.LocalizationValue(BadgeStrings.ladderNameKey(id)))
    }

    private func tierName(_ tier: BadgeTier) -> String {
        String(localized: String.LocalizationValue(BadgeStrings.tierNameKey(tier)))
    }

    /// The localized milestone name, or the award doc's denormalized Swedish
    /// name, or the key — Android's `milestoneNameFor`.
    private func milestoneName(_ milestone: MilestoneBadge) -> String {
        if let key = BadgeStrings.badgeNameKey(for: milestone.key) {
            return String(localized: String.LocalizationValue(key))
        }
        return milestone.fallbackName ?? milestone.key
    }
}

/// One badge in the wall: a tier-coloured medallion, its label, and either its
/// unlock date (earned) or a "no tier yet" caption (locked) — Android's
/// `BadgeMedallionTile`.
private struct BadgeMedallionTile: View {
    let tier: BadgeTier?
    let earned: Bool
    let label: String
    let ladderName: String
    let awardedAt: Date?

    var body: some View {
        VStack(spacing: KccSpacing.s1) {
            BadgeMedallion(tier: tier, earned: earned)
            Text(label)
                .font(.system(size: KccTypeScale.caption, weight: KccTypeScale.medium))
                .foregroundStyle(earned ? Color.primary : Color.secondary)
                .multilineTextAlignment(.center)
            if let caption {
                Text(caption)
                    .font(.system(size: KccTypeScale.caption))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity, alignment: .top)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(accessibilityLabel))
    }

    /// The second caption line — the unlock date (earned, dated), the "no
    /// tier yet" hint (locked), or nil when earned but undated (an
    /// admin-granted award whose document carries no `awardedAt`). Nil omits
    /// the line entirely rather than reserving blank vertical space for it —
    /// Android's `BadgeMedallionTile` likewise renders no caption `Text` at
    /// all when its `caption` parameter is null.
    private var caption: String? {
        if earned, let awardedAt {
            return BadgeText.awardedOn(date: awardedAt)
        }
        if earned {
            return nil
        }
        return String(localized: "badgeShowcase.noTierYet")
    }

    /// The spoken label. For an earned, dated badge this appends the unlock
    /// date so VoiceOver carries the same information the caption line shows
    /// visually — the tile collapses its children into this one label
    /// (`accessibilityElement(children: .ignore)`), so without this the date
    /// would be silently dropped rather than merely unspoken.
    private var accessibilityLabel: String {
        let key = earned ? "badgeShowcase.medallionEarned" : "badgeShowcase.medallionLocked"
        let spoken = tier == nil ? label : "\(ladderName) \(label)"
        let base = String(format: String(localized: String.LocalizationValue(key)), spoken)
        guard earned, let awardedAt else { return base }
        return "\(base), \(BadgeText.awardedOn(date: awardedAt))"
    }
}

/// The medallion glyph: a tier-coloured rosette, dimmed when locked. A single
/// SF Symbol stands in for Android's per-ladder drawn glyph; the tier colour
/// carries the rank, and the greyed treatment carries the locked state.
private struct BadgeMedallion: View {
    let tier: BadgeTier?
    let earned: Bool

    private static let size: CGFloat = 44

    var body: some View {
        Circle()
            .fill(earned ? fillColor.opacity(0.18) : Color.secondary.opacity(0.12))
            .frame(width: Self.size, height: Self.size)
            .overlay(
                Image(systemName: "rosette")
                    .font(.system(size: Self.size * 0.5, weight: .semibold))
                    .foregroundStyle(earned ? fillColor : Color.secondary.opacity(0.5))
            )
            .overlay(
                Circle().strokeBorder(
                    earned ? fillColor.opacity(0.55) : Color.secondary.opacity(0.25),
                    lineWidth: 1.5
                )
            )
    }

    /// Tier colour, from the design palette. A milestone (no tier) uses the
    /// brand gold, like an earned reward.
    private var fillColor: Color {
        switch tier {
        case .brons: return Color(red: 176 / 255, green: 121 / 255, blue: 65 / 255)
        case .silver: return KccPalette.silverGrey
        case .guld: return KccPalette.crownGold
        case .platina: return Color(red: 156 / 255, green: 174 / 255, blue: 186 / 255)
        case nil: return KccPalette.crownGold
        }
    }
}

// MARK: - Formatted strings

/// Formats the badge wall's positional-argument strings from the generated
/// catalog — the iOS analog of Android resolving `stringResource(id, args…)`.
/// Kept in one place so every `%1$…` template is filled the same way.
enum BadgeText {
    /// `%1$lld of %2$lld unlocked`.
    static func subtitle(earned: Int, total: Int) -> String {
        // The catalog string is `%1$lld of %2$lld unlocked` (a 64-bit format
        // specifier); pass Int64 explicitly rather than relying on Int's
        // CVarArg encoding to happen to match on every current ABI.
        String(format: template("badgeShowcase.subtitle"), Int64(earned), Int64(total))
    }

    /// `Next: %1$@`.
    static func nextTier(tierName: String) -> String {
        String(format: template("badgeShowcase.nextTier"), tierName)
    }

    /// `%1$@ / %2$@`.
    static func progressCount(current: String, target: String) -> String {
        String(format: template("badgeShowcase.progressCount"), current, target)
    }

    /// The ladder's requirement sentence with its single threshold.
    static func ladderRequirement(_ id: BadgeLadderId, threshold: String) -> String {
        String(format: template(BadgeStrings.ladderRequirementKey(id)), threshold)
    }

    /// `Unlocked <date>` — the award's date, medium style, in the current
    /// locale.
    static func awardedOn(date: Date) -> String {
        let formatted = date.formatted(date: .abbreviated, time: .omitted)
        return String(format: template("badgeShowcase.awardedOn"), formatted)
    }

    private static func template(_ key: String) -> String {
        String(localized: String.LocalizationValue(key))
    }
}

#Preview("Loaded") {
    BadgesWall(coordinator: BadgesCoordinator(repository: PreviewBadgesRepository()))
}

/// A tiny in-file fake so the SwiftUI preview renders a populated wall without
/// Firebase. Not used outside previews.
private final class PreviewBadgesRepository: BadgesRepository, @unchecked Sendable {
    func observeBadges(uid: String) -> AsyncStream<BadgesSnapshot> {
        AsyncStream { continuation in
            continuation.yield(
                .loaded([
                    Badge(key: "kronjagare_brons", fallbackName: "Kronjägare Brons", awardedAt: Date()),
                    Badge(key: "traffrav_brons", fallbackName: "Träffräv Brons", awardedAt: Date()),
                    Badge(key: "first_event", fallbackName: "Första träffen", awardedAt: Date()),
                ])
            )
            continuation.finish()
        }
    }

    func fetchMyProgress() async -> BadgeCounters? {
        BadgeCounters(crownsCollected: 12, verifiedEventsAttended: 2)
    }

    func currentUserId() -> String? { "preview-uid" }
}
