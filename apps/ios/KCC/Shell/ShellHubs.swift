import SwiftUI

/// Panel CONTENT for the translucent shell tabs — the iOS counterpart of
/// Android's `shell/ShellHubs.kt` hub screens, minimal on purpose: only the
/// entries whose features exist on iOS are rendered, and a tab with no ported
/// feature yet says so honestly instead of dead-ending.

/// The Social hub panel. Android's Social hub lists Events / Crown Hunt /
/// Leaderboard / Partners (label-sorted, unavailable entries omitted); only
/// the events slice is ported, so this hub carries the one entry. The entry
/// stays present in a config-less build — the events route itself renders the
/// coordinator's unavailable placeholder, never a crash.
struct SocialHubPanel: View {
    /// Opens ``ShellRoute/events`` full-screen via the shell's route stack.
    let onOpenEvents: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s4) {
            Text("shell.socialTitle")
                .font(.system(size: KccTypeScale.headingLg, weight: KccTypeScale.semibold))

            Button(action: onOpenEvents) {
                HStack(spacing: KccSpacing.s3) {
                    Image(systemName: "calendar")
                        .foregroundStyle(.secondary)
                    Text("shell.socialEvents")
                        .font(.system(size: KccTypeScale.bodyMd))
                    Spacer()
                    Image(systemName: "chevron.forward")
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Spacer()
        }
        .padding(KccSpacing.s6)
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
    SocialHubPanel(onOpenEvents: {})
}

#Preview("Coming soon") {
    ComingSoonPanel(title: "shell.tabHistory")
}
