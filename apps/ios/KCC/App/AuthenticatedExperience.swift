import SwiftUI

/// Gates the signed-in shell on the live public profile, matching Android's
/// Loading → Onboarding → Main routing. Listener failures fail open to the
/// shell so an offline snapshot cannot trap an existing member forever.
struct AuthenticatedExperience: View {
    let uid: String
    let displayName: String?
    @Bindable var session: AuthSession
    let access: AccountAccess
    let featureFlags: FeatureFlags
    @State private var profile: ProfileCoordinator
    @State private var onboarding: OnboardingCoordinator

    init(
        uid: String,
        displayName: String?,
        session: AuthSession,
        access: AccountAccess,
        featureFlags: FeatureFlags
    ) {
        self.uid = uid
        self.displayName = displayName
        self.session = session
        self.access = access
        self.featureFlags = featureFlags
        let repository = FirebaseUserProfileRepository.createIfAvailable()
        _profile = State(initialValue: ProfileCoordinator(repository: repository, uid: uid))
        _onboarding = State(initialValue: OnboardingCoordinator(
            repository: FirebaseOnboardingRepository.createIfAvailable()
        ))
    }

    var body: some View {
        Group {
            switch profile.state {
            case .loading:
                ProgressView("onboarding.loading")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .loaded(let value):
                if value?.onboardingComplete == true {
                    WelcomeGateView(
                        uid: uid,
                        session: session,
                        access: access,
                        featureFlags: featureFlags
                    )
                    .id(uid)
                } else {
                    OnboardingScreen(coordinator: onboarding)
                }
            case .failed, .unavailable:
                ShellView(
                    session: session,
                    authenticatedUid: uid,
                    access: access,
                    featureFlags: featureFlags
                )
            }
        }
        .task { profile.start() }
    }
}
