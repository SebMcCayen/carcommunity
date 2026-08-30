import SwiftUI

/// Signed-in own-profile surface — now backed by the live `users/{uid}`
/// Firestore document via ``ProfileCoordinator``, mirroring the VIEW-mode
/// fields of Android's `profile/ProfileScreen.kt`: circular avatar (resolved
/// download URL rendered with AsyncImage — Coil on Android), display name,
/// and bio. Edit mode, social links, badges, points and stats come with
/// their own slices.
///
/// The auth display name remains the fallback identity surface while the
/// document is loading, unavailable (config-less build) or errored — the
/// screen never blanks a name it already had, and per the PII rules the uid
/// is never shown or logged. Sign-out stays a DIRECT action with no
/// confirmation dialog, exactly like Android's map-home profile menu entry.
///
/// The repository and the uid are wired at feature level through the
/// `createIfAvailable` factories (the same construction pattern Android
/// uses), so the shell keeps constructing this screen with identity +
/// closures only; the coordinator-taking initializer is the seam previews
/// use.
struct ProfileScreen: View {
    /// The signed-in member's auth display name; the fallback while the
    /// profile document has not loaded (or has no name of its own).
    let displayName: String?
    let onSignOut: () -> Void
    let onBack: () -> Void

    @State private var coordinator: ProfileCoordinator

    /// Production wiring (unchanged shell-facing signature): builds the
    /// coordinator from the feature-level factories. In a config-less build
    /// both factories return nil and the coordinator settles on
    /// ``ProfileUiState/unavailable`` — the screen then renders exactly the
    /// pre-Firestore fallback.
    init(displayName: String?, onSignOut: @escaping () -> Void, onBack: @escaping () -> Void) {
        self.init(
            displayName: displayName,
            onSignOut: onSignOut,
            onBack: onBack,
            coordinator: ProfileCoordinator(
                repository: FirebaseUserProfileRepository.createIfAvailable(),
                uid: Self.signedInUid()
            )
        )
    }

    /// Preview/test seam: inject a coordinator (typically fed by a fake
    /// repository).
    init(
        displayName: String?,
        onSignOut: @escaping () -> Void,
        onBack: @escaping () -> Void,
        coordinator: ProfileCoordinator
    ) {
        self.displayName = displayName
        self.onSignOut = onSignOut
        self.onBack = onBack
        _coordinator = State(initialValue: coordinator)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s4) {
            Button(action: onBack) {
                Label("profile.back", systemImage: "chevron.backward")
                    .font(.system(size: KccTypeScale.bodyMd))
            }

            Text("profile.title")
                .font(.system(size: KccTypeScale.headingLg, weight: .semibold))
                .padding(.top, KccSpacing.s2)

            // Avatar + name centered as a unit, like Android's AvatarSection.
            VStack(spacing: KccSpacing.s2) {
                avatar
                Text("auth.loggedInAs")
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(.secondary)
                nameText
            }
            .frame(maxWidth: .infinity)

            bioSection

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
        .task { coordinator.start() }
    }

    // MARK: - Sections

    /// 96pt circle: the resolved avatar via AsyncImage, or the "?"
    /// placeholder tint — Android's `AvatarSection` (a failed/missing URL
    /// keeps the placeholder; a picture is cosmetic, never an error state).
    private var avatar: some View {
        ZStack {
            Circle()
                .fill(Color(.secondarySystemBackground))
            if let url = coordinator.avatarURL {
                AsyncImage(url: url) { image in
                    image
                        .resizable()
                        .scaledToFill()
                } placeholder: {
                    avatarPlaceholder
                }
            } else {
                avatarPlaceholder
            }
        }
        .frame(width: 96, height: 96)
        .clipShape(Circle())
        .accessibilityLabel(Text("profile.avatarAlt"))
    }

    private var avatarPlaceholder: some View {
        Text(verbatim: "?")
            .font(.system(size: KccTypeScale.headingLg))
            .foregroundStyle(.secondary)
    }

    /// The profile displayName once loaded and non-blank; the auth display
    /// name as fallback while loading/unavailable/errored (and when the doc
    /// has no usable name); the explicit empty-name placeholder only when
    /// neither exists after load — Android's `profile_emptyDisplayName`.
    @ViewBuilder
    private var nameText: some View {
        if let name = resolvedDisplayName {
            Text(name)
                .font(.system(size: KccTypeScale.titleMd, weight: .medium))
        } else if case .loaded = coordinator.state {
            Text("profile.emptyDisplayName")
                .font(.system(size: KccTypeScale.titleMd, weight: .medium))
                .foregroundStyle(.secondary)
        }
    }

    private var resolvedDisplayName: String? {
        if case .loaded(let profile) = coordinator.state,
            let name = profile?.displayName,
            !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        {
            return name
        }
        return displayName
    }

    /// Bio once loaded (empty-bio placeholder when blank — Android's
    /// `profile_emptyBio`), a small spinner during first load, a generic
    /// retry-free notice on listener error (the listener self-corrects on a
    /// later snapshot — Android's `profile_loadError` posture). Unavailable
    /// renders nothing extra.
    @ViewBuilder
    private var bioSection: some View {
        switch coordinator.state {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity)
        case .loaded(let profile):
            if let bio = profile?.bio,
                !bio.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            {
                Text(bio)
                    .font(.system(size: KccTypeScale.bodyMd))
            } else {
                Text("profile.emptyBio")
                    .font(.system(size: KccTypeScale.bodyMd))
                    .foregroundStyle(.secondary)
            }
        case .failed:
            Text("profile.loadError")
                .font(.system(size: KccTypeScale.bodySm))
                .foregroundStyle(.secondary)
        case .unavailable:
            EmptyView()
        }
    }

    // MARK: - Wiring

    /// The signed-in uid from the process-wide auth repository, nil when
    /// Firebase is unconfigured or no session exists. Read here (feature
    /// level) so the shell-facing init stays identity + closures only.
    private static func signedInUid() -> String? {
        if case .signedIn(let uid, _)? = FirebaseAuthRepository.createIfAvailable()?.authState {
            return uid
        }
        return nil
    }
}

#Preview("With display name (unavailable build)") {
    ProfileScreen(displayName: "Sebbe", onSignOut: {}, onBack: {})
}

#Preview("No display name (unavailable build)") {
    ProfileScreen(displayName: nil, onSignOut: {}, onBack: {})
}

#Preview("Loaded profile") {
    let repository = PreviewUserProfileRepository(
        profile: UserProfile(
            displayName: "Sebbe",
            bio: "Bakhjulsdriven vardag. E46:an är aldrig färdig.",
            avatarPath: nil
        )
    )
    ProfileScreen(
        displayName: "Sebbe",
        onSignOut: {},
        onBack: {},
        coordinator: ProfileCoordinator(repository: repository, uid: "preview-uid")
    )
}

/// Preview-only scripted repository: yields one settled snapshot and stays
/// open, like a real listener. Never resolves an avatar (placeholder shows).
private final class PreviewUserProfileRepository: UserProfileRepository, @unchecked Sendable {
    private let profile: UserProfile?

    init(profile: UserProfile?) {
        self.profile = profile
    }

    func profileUpdates(uid: String) -> AsyncStream<UserProfileSnapshot> {
        let profile = profile
        return AsyncStream { continuation in
            continuation.yield(.loaded(profile))
        }
    }

    func avatarDownloadURL(for avatarPath: String) async -> URL? { nil }
}
