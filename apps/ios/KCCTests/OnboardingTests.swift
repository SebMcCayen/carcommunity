import XCTest
@testable import KCC

final class OnboardingTests: XCTestCase {
    private final class FakeRepository: OnboardingRepository, @unchecked Sendable {
        var submissions: [(String, Bool?)] = []
        var failure: Error?
        func completeOnboarding(displayName: String, anonymousPartnerStatsOptIn: Bool?) async throws {
            if let failure { throw failure }
            submissions.append((displayName, anonymousPartnerStatsOptIn))
        }
    }
    private struct Failure: Error {}

    func testFormRequiresEveryConsentAndAValidTrimmedName() {
        XCTAssertFalse(OnboardingForm.canSubmit(licenceConfirmed: false, termsAccepted: true, privacyAccepted: true, displayName: "Ada"))
        XCTAssertFalse(OnboardingForm.canSubmit(licenceConfirmed: true, termsAccepted: true, privacyAccepted: true, displayName: "  "))
        XCTAssertTrue(OnboardingForm.canSubmit(licenceConfirmed: true, termsAccepted: true, privacyAccepted: true, displayName: " Ada "))
        XCTAssertEqual(OnboardingForm.normalizedDisplayName(" Ada "), "Ada")
    }

    @MainActor
    func testSubmitCarriesCanonicalNameAndPartnerChoice() async {
        let repository = FakeRepository()
        let coordinator = OnboardingCoordinator(repository: repository)
        await coordinator.submit(displayName: " Ada ", anonymousPartnerStatsOptIn: false)
        XCTAssertEqual(coordinator.status, .done)
        XCTAssertEqual(repository.submissions.count, 1)
        XCTAssertEqual(repository.submissions.first?.0, "Ada")
        XCTAssertEqual(repository.submissions.first?.1, false)
    }

    @MainActor
    func testFailureIsGenericAndRetryable() async {
        let repository = FakeRepository(); repository.failure = Failure()
        let coordinator = OnboardingCoordinator(repository: repository)
        await coordinator.submit(displayName: "Ada", anonymousPartnerStatsOptIn: true)
        XCTAssertEqual(coordinator.status, .failed)
        coordinator.resetFailure()
        XCTAssertEqual(coordinator.status, .idle)
    }
}
