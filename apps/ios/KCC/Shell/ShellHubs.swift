import SwiftUI

/// Panel CONTENT for the translucent shell tabs — the iOS counterpart of
/// Android's `shell/ShellHubs.kt` hub screens, minimal on purpose: only the
/// entries whose features exist on iOS are rendered, and a tab with no ported
/// feature yet says so honestly instead of dead-ending.

/// The Social hub panel. Android's Social hub lists Events / Crown Hunt /
/// Leaderboard / Partners (label-sorted, unavailable entries omitted). iOS
/// currently has the first three feature slices, so those are the entries this
/// panel exposes; Partners remains absent until its repository and screen land.
struct SocialHubPanel: View {
    @Environment(\.locale) private var locale

    let crownHuntEnabled: Bool
    let onOpenEvents: () -> Void
    let onOpenCrownHunt: () -> Void
    let onOpenLeaderboard: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s4) {
            Text("shell.socialTitle")
                .font(.system(size: KccTypeScale.headingLg, weight: KccTypeScale.semibold))

            ForEach(entries) { entry in
                hubRow(label: entry.label, icon: entry.icon, action: entry.action)
            }

            Spacer()
        }
        .padding(KccSpacing.s6)
    }

    private var entries: [SocialHubEntry] {
        var entries = [
            SocialHubEntry(
                id: .events,
                label: "shell.socialEvents",
                localizedLabel: String(localized: "shell.socialEvents", locale: locale),
                icon: "calendar",
                action: onOpenEvents
            ),
            SocialHubEntry(
                id: .leaderboard,
                label: "shell.socialLeaderboard",
                localizedLabel: String(localized: "shell.socialLeaderboard", locale: locale),
                icon: "trophy",
                action: onOpenLeaderboard
            )
        ]
        if crownHuntEnabled {
            entries.append(
                SocialHubEntry(
                    id: .crownHunt,
                    label: "shell.socialCrownHunt",
                    localizedLabel: String(localized: "shell.socialCrownHunt", locale: locale),
                    icon: "crown",
                    action: onOpenCrownHunt
                )
            )
        }
        return entries.sorted {
            $0.localizedLabel.localizedStandardCompare($1.localizedLabel) == .orderedAscending
        }
    }

    private func hubRow(
        label: LocalizedStringKey,
        icon: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: KccSpacing.s3) {
                Image(systemName: icon)
                    .foregroundStyle(.secondary)
                Text(label)
                    .font(.system(size: KccTypeScale.bodyMd))
                Spacer()
                Image(systemName: "chevron.forward")
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct SocialHubEntry: Identifiable {
    enum ID: Hashable {
        case events
        case crownHunt
        case leaderboard
    }

    let id: ID
    let label: LocalizedStringKey
    let localizedLabel: String
    let icon: String
    let action: () -> Void
}

/// Placeholder panel content for the tabs whose hubs are not ported yet
/// (History): the localized tab title plus the shared
/// `shell.comingSoon` notice. Exists so those tabs can already render as
/// translucent panels — making the `translucentPanelTabs` map-cover rule real
/// — without inventing hub entries their features cannot back.
struct ComingSoonPanel: View {
    let title: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s4) {
            Text(title)
                .font(.system(size: KccTypeScale.headingLg, weight: KccTypeScale.semibold))
            Text("shell.comingSoon")
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding(KccSpacing.s6)
    }
}

#Preview("Social hub") {
    SocialHubPanel(
        crownHuntEnabled: true,
        onOpenEvents: {},
        onOpenCrownHunt: {},
        onOpenLeaderboard: {}
    )
}

#Preview("Coming soon") {
    ComingSoonPanel(title: "shell.tabHistory")
}
