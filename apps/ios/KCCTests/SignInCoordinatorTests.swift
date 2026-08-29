import XCTest

@testable import KCC

/// Unit tests for the pure sign-in orchestration — the iOS counterpart of the
/// coordinator coverage in Android's auth tests: every provider/exchange
/// outcome maps to the right ``SignInStatus``, user cancellation is never
/// reported, and failures are reported PII-safe with the correct step.
final class SignInCoordinatorTests: XCTestCase {

    // MARK: - fakes

    private struct FakeProvider: AppleIDTokenProvider {
        let result: Result<AppleIDTokenPayload, Error>

        func fetchAppleIDToken() async throws -> AppleIDTokenPayload {
            try result.get()
        }
    }

    private final class FakeRepository: AuthRepository, @unchecked Sendable {
        var signInError: Error?
        private(set) var signInCalls = 0
        private(set) var lastPayload: AppleIDTokenPayload?

        var authState: AuthState = .signedOut

        func authStateUpdates() -> AsyncStream<AuthState> {
            AsyncStream { $0.yield(authState); $0.finish() }
        }

        func signIn(with payload: AppleIDTokenPayload) async throws {
            signInCalls += 1
            lastPayload = payload
            if let signInError { throw signInError }
        }

        func signOut() throws {}
    }

    private final class RecordingReporter: SignInFailureReporter, @unchecked Sendable {
        private(set) var reports: [SignInFailureDetails] = []

        func reportSignInFailure(_ details: SignInFailureDetails) {
            reports.append(details)
        }
    }

    private struct ProviderError: Error {}

    private static let payload = AppleIDTokenPayload(
        identityToken: "header.claims.",
        rawNonce: "nonce",
        fullName: nil
    )

    private func makeCoordinator(
        provider: Result<AppleIDTokenPayload, Error> = .success(payload),
        repository: FakeRepository = FakeRepository(),
        reporter: RecordingReporter = RecordingReporter()
    ) async -> (SignInCoordinator, FakeRepository, RecordingReporter) {
        let coordinator = await SignInCoordinator(
            tokenProvider: FakeProvider(result: provider),
            repository: repository,
            failureReporter: reporter
        )
        return (coordinator, repository, reporter)
    }

    // MARK: - outcomes

    func testSuccessfulSignInExchangesTheTokenAndReturnsToIdle() async {
        let (coordinator, repository, reporter) = await makeCoordinator()
        await coordinator.signIn()
        let status = await coordinator.status
        XCTAssertEqual(status, .idle)
        XCTAssertEqual(repository.signInCalls, 1)
        XCTAssertEqual(repository.lastPayload?.rawNonce, "nonce")
        XCTAssertTrue(reporter.reports.isEmpty)
    }

    func testUserCancellationReturnsToIdleAndIsNeverReported() async {
        // The user dismissed the Apple ID sheet: not a fault. Idle, not
        // failed — the screen returns to rest instead of accusing them of an
        // error — and nothing is reported (Android's issue #457 rule).
        let (coordinator, repository, reporter) = await makeCoordinator(
            provider: .failure(SignInCancelledError())
        )
        await coordinator.signIn()
        let status = await coordinator.status
        XCTAssertEqual(status, .idle)
        XCTAssertEqual(repository.signInCalls, 0)
        XCTAssertTrue(reporter.reports.isEmpty)
    }

    func testUnavailableConfigurationFailsWithoutAReport() async {
        // Configuration gap, not a runtime error — surfaced to the UI but
        // never reported as a failure.
        let (coordinator, _, reporter) = await makeCoordinator(
            provider: .failure(SignInUnavailableError())
        )
        await coordinator.signIn()
        let status = await coordinator.status
        XCTAssertEqual(status, .failed(.unavailable))
        XCTAssertTrue(reporter.reports.isEmpty)
    }

    func testProviderFailureReportsTheCredentialFetchStep() async {
        let (coordinator, repository, reporter) = await makeCoordinator(
            provider: .failure(ProviderError())
        )
        await coordinator.signIn()
        let status = await coordinator.status
        XCTAssertEqual(status, .failed(.generic))
        XCTAssertEqual(repository.signInCalls, 0)
        XCTAssertEqual(reporter.reports.count, 1)
        XCTAssertEqual(reporter.reports.first?.step, .credentialFetch)
        XCTAssertEqual(reporter.reports.first?.errorType, "ProviderError")
    }

    func testExchangeFailureReportsTheFirebaseExchangeStepWithDiagnosticCode() async {
        let repository = FakeRepository()
        repository.signInError = SignInFailedError(
            underlying: ProviderError(),
            diagnosticCode: "invalidCredential"
        )
        let (coordinator, _, reporter) = await makeCoordinator(repository: repository)
        await coordinator.signIn()
        let status = await coordinator.status
        XCTAssertEqual(status, .failed(.generic))
        XCTAssertEqual(reporter.reports.count, 1)
        XCTAssertEqual(reporter.reports.first?.step, .firebaseExchange)
        XCTAssertEqual(reporter.reports.first?.errorType, "SignInFailedError")
        // The stable, PII-safe Firebase error-code name travels with the
        // report; messages and tokens never do.
        XCTAssertEqual(reporter.reports.first?.statusCode, "invalidCredential")
    }

    func testUnavailableSurfacedAtTheExchangeMapsToUnavailable() async {
        let repository = FakeRepository()
        repository.signInError = SignInUnavailableError()
        let (coordinator, _, reporter) = await makeCoordinator(repository: repository)
        await coordinator.signIn()
        let status = await coordinator.status
        XCTAssertEqual(status, .failed(.unavailable))
        XCTAssertTrue(reporter.reports.isEmpty)
    }

    func testResetFailureClearsAFailedStateOnly() async {
        let (coordinator, _, _) = await makeCoordinator(
            provider: .failure(ProviderError())
        )
        await coordinator.signIn()
        var status = await coordinator.status
        XCTAssertEqual(status, .failed(.generic))
        await coordinator.resetFailure()
        status = await coordinator.status
        XCTAssertEqual(status, .idle)
    }

    // MARK: - describeFailure

    func testDescribeFailureUsesTheTypeNameAndCarriedCode() {
        let details = SignInCoordinator.describeFailure(
            SignInFailedError(underlying: ProviderError(), diagnosticCode: "userDisabled"),
            step: .firebaseExchange
        )
        XCTAssertEqual(details.errorType, "SignInFailedError")
        XCTAssertEqual(details.statusCode, "userDisabled")
        XCTAssertEqual(details.step, .firebaseExchange)
    }

    func testDescribeFailureWithoutACarrierHasNoCode() {
        let details = SignInCoordinator.describeFailure(ProviderError(), step: .credentialFetch)
        XCTAssertEqual(details.errorType, "ProviderError")
        XCTAssertNil(details.statusCode)
    }

    // MARK: - wire names (cross-platform diagnostics contract)

    func testStepWireNamesMatchAndroid() {
        // Backend diagnostics bucket both platforms by these strings —
        // Android's SignInStep.wireName values. Do not change one side alone.
        XCTAssertEqual(SignInStep.credentialFetch.rawValue, "credential_fetch")
        XCTAssertEqual(SignInStep.firebaseExchange.rawValue, "firebase_exchange")
    }
}
