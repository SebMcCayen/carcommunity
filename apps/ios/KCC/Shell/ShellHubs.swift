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
    let onOpenEvents: () -> Void
    let onOpenCrownHunt: () -> Void
    let onOpenLeaderboard: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s4) {
            Text("shell.socialTitle")
                .font(.system(size: KccTypeScale.headingLg, weight: KccTypeScale.semibold))

            hubRow(label: "shell.socialEvents", icon: "calendar", action: onOpenEvents)
            hubRow(label: "shell.socialCrownHunt", icon: "crown", action: onOpenCrownHunt)
            hubRow(label: "shell.socialLeaderboard", icon: "trophy", action: onOpenLeaderboard)

            Spacer()
        }
        .padding(KccSpacing.s6)
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
    SocialHubPanel(onOpenEvents: {}, onOpenCrownHunt: {}, onOpenLeaderboard: {})
}

#Preview("Coming soon") {
    ComingSoonPanel(title: "shell.tabHistory")
}
