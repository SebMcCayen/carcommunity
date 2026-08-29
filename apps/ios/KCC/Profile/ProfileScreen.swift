import SwiftUI

/// Minimal signed-in profile surface — NOT a port of Android's full
/// `profile/ProfileScreen.kt` (avatar, bio, badges, points come with their own
/// slices). This is only the parity-faithful auth round-trip closer: see who
/// you are, and sign out. Semantics mirror Android's affordances — the
/// `auth.loggedInAs` caption with the display name shown only when present
/// (`home/HomeScreen.kt`), and sign-out as a DIRECT action with no
/// confirmation dialog, exactly like the map-home profile menu's sign-out
/// entry (`AuthenticatedApp.kt` `profileMenuEntries`).
///
/// Pure view: identity in, closures out, so it previews and composes without
/// an ``AuthSession``. Per the PII rules the uid is never shown or logged —
/// the caption plus the display name is the whole identity surface.
struct ProfileScreen: View {
    /// The signed-in member's display name; nil (no name set yet) shows just
    /// the signed-in caption, matching Android's null-name rendering.
    let displayName: String?
    let onSignOut: () -> Void
    let onBack: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s4) {
            Button(action: onBack) {
                Label("profile.back", systemImage: "chevron.backward")
                    .font(.system(size: KccTypeScale.bodyMd))
            }

            Text("profile.title")
                .font(.system(size: KccTypeScale.headingLg, weight: .semibold))
                .padding(.top, KccSpacing.s2)

            Text("auth.loggedInAs")
                .font(.system(size: KccTypeScale.bodySm))
                .foregroundStyle(.secondary)
            if let displayName {
                Text(displayName)
                    .font(.system(size: KccTypeScale.titleMd, weight: .medium))
            }

            Spacer()

            Button(action: onSignOut) {
                Text("auth.signOut")
                    .font(.system(size: KccTypeScale.bodyMd, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
        }
        .padding(KccSpacing.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        // The screen renders as a full-screen overlay above the tab shell, so
        // it must paint an opaque, scheme-aware background of its own.
        .background(.background, ignoresSafeAreaEdges: .all)
    }
}

#Preview("With display name") {
    ProfileScreen(displayName: "Sebbe", onSignOut: {}, onBack: {})
}

#Preview("No display name") {
    ProfileScreen(displayName: nil, onSignOut: {}, onBack: {})
}
