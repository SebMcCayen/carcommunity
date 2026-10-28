import XCTest
@testable import KCC

final class AuthorizedAccountDeletionTests: XCTestCase {
    func testUncertainBackendDeliveryDoesNotRepeatAppleRevocation() async throws {
        let fixture = DeletionAuthorizationFixture(failBackendOnce: true)
        let repository = AuthorizedAccountDeletionRepository(repository: fixture, authorization: fixture)
        do { try await repository.deleteAccount(reason: nil); XCTFail("Expected network failure") }
        catch is KccFunctionsError {}
        try await repository.deleteAccount(reason: nil)
        let events = await fixture.events
        XCTAssertEqual(events, ["authorize", "backend", "backend"])
    }
    func testAccountReplacementDuringAuthorizationNeverStartsBackendDeletion() async {
        let fixture = DeletionAuthorizationFixture(replaceDuringAuthorization: true)
        let repository = AuthorizedAccountDeletionRepository(repository: fixture, authorization: fixture)
        do { try await repository.deleteAccount(reason: nil); XCTFail("Expected cancellation") }
        catch is CancellationError {} catch { XCTFail("Unexpected failure") }
        let events = await fixture.events
        XCTAssertEqual(events, ["authorize"])
    }
    func testCancelledAppleSheetDoesNotDeleteOrMarkTheAttemptAuthorized() async throws {
        let fixture = DeletionAuthorizationFixture(cancelOnce: true)
        let repository = AuthorizedAccountDeletionRepository(repository: fixture, authorization: fixture)
        do { try await repository.deleteAccount(reason: nil); XCTFail("Expected cancellation") }
        catch is CancellationError {}
        try await repository.deleteAccount(reason: nil)
        let events = await fixture.events
        XCTAssertEqual(events, ["authorize", "authorize", "backend"])
    }
}

private actor DeletionAuthorizationFixture: AccountDeletionRepository, AccountDeletionAuthorizing {
    var events: [String] = []
    var current = true
    var failBackendOnce: Bool
    var replaceDuringAuthorization: Bool
    var cancelOnce: Bool
    init(failBackendOnce: Bool = false, replaceDuringAuthorization: Bool = false, cancelOnce: Bool = false) {
        self.failBackendOnce = failBackendOnce
        self.replaceDuringAuthorization = replaceDuringAuthorization
        self.cancelOnce = cancelOnce
    }
    func assertCurrentSession() throws {
        if !current { throw CancellationError() }
    }
    func authorizeDeletion() throws {
        events.append("authorize")
        if cancelOnce { cancelOnce = false; throw SignInCancelledError() }
        if replaceDuringAuthorization { current = false }
    }
    func deleteAccount(reason: String?) throws {
        events.append("backend")
        if failBackendOnce { failBackendOnce = false; throw KccFunctionsError(code: .unavailable) }
    }
}
