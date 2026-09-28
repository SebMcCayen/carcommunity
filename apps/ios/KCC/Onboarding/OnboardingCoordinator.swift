import Observation

@MainActor
@Observable
final class OnboardingCoordinator {
    private let repository: OnboardingRepository?
    private(set) var status: OnboardingStatus = .idle

    init(repository: OnboardingRepository?) { self.repository = repository }

    func submit(displayName: String, anonymousPartnerStatsOptIn: Bool?) async {
        guard status != .submitting else { return }
        guard let repository else { status = .failed; return }
        guard let name = OnboardingForm.normalizedDisplayName(displayName) else { return }
        status = .submitting
        do {
            try await repository.completeOnboarding(
                displayName: name,
                anonymousPartnerStatsOptIn: anonymousPartnerStatsOptIn
            )
            status = .done
        } catch is CancellationError {
            status = .idle
        } catch {
            status = .failed
        }
    }

    func resetFailure() { if status == .failed { status = .idle } }
}
