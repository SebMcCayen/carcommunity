import SwiftUI

/// Gates the signed-in shell on the live public profile, matching Android's
/// Loading → Onboarding → Main routing. Profile failures fail closed until
/// this session has observed a completed onboarding timestamp.
struct AuthenticatedExperience: View {
    let uid: String
    let displayName: String?
    @Bindable var session: AuthSession
    let access: AccountAccess
    let featureFlags: FeatureFlags
    let diagnostics: IOSDiagnosticsComposition
    @State private var profile: ProfileCoordinator
    @State private var onboarding: OnboardingCoordinator
    @State private var signOutFailed = false

    init(
        uid: String,
        displayName: String?,
        session: AuthSession,
        access: AccountAccess,
        featureFlags: FeatureFlags,
        diagnostics: IOSDiagnosticsComposition
    ) {
        self.uid = uid
        self.displayName = displayName
        self.session = session
        self.access = access
        self.featureFlags = featureFlags
        self.diagnostics = diagnostics
        let repository = FirebaseUserProfileRepository.createIfAvailable()
        _profile = State(initialValue: ProfileCoordinator(repository: repository, uid: uid))
        _onboarding = State(initialValue: OnboardingCoordinator(
            repository: FirebaseOnboardingRepository.createIfAvailable()
        ))
    }

    var body: some View {
        Group {
            switch AuthenticatedExperienceRoute.resolve(
                profileState: profile.state,
                hasVerifiedOnboarding: profile.hasVerifiedOnboarding
            ) {
            case .loading:
                ProgressView("onboarding.loading")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .onboarding:
                OnboardingScreen(coordinator: onboarding)
            case .shell:
                WelcomeGateView(
                    uid: uid,
                    session: session,
                    access: access,
                    featureFlags: featureFlags,
                    diagnostics: diagnostics
                )
                .id(uid)
            case .recovery(let canRetry):
                profileRecovery(canRetry: canRetry)
            }
        }
        .task { profile.start() }
    }

    private func profileRecovery(canRetry: Bool) -> some View {
        VStack(spacing: KccSpacing.s4) {
            Text("onboarding.error")
                .multilineTextAlignment(.center)
                .foregroundStyle(KccPalette.errorRed)
            if canRetry {
                Button("notifications.retry") {
                    signOutFailed = false
                    profile.retry()
                }
                .buttonStyle(.borderedProminent)
            }
            Button("auth.signOut") {
                signOutFailed = !session.signOut(ifSignedInAs: uid)
            }
            .buttonStyle(.bordered)
            if signOutFailed {
                Text("auth.signOutError")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(KccPalette.errorRed)
            }
        }
        .padding(KccSpacing.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

enum AuthenticatedExperienceRoute: Equatable {
    case loading
    case onboarding
    case shell
    case recovery(canRetry: Bool)

    static func resolve(
        profileState: ProfileUiState,
        hasVerifiedOnboarding: Bool
    ) -> AuthenticatedExperienceRoute {
        switch profileState {
        case .loading:
            return hasVerifiedOnboarding ? .shell : .loading
        case .loaded(let profile):
            return profile?.onboardingComplete == true ? .shell : .onboarding
        case .failed:
            return hasVerifiedOnboarding ? .shell : .recovery(canRetry: true)
        case .unavailable:
            return hasVerifiedOnboarding ? .shell : .recovery(canRetry: false)
        }
    }
}
