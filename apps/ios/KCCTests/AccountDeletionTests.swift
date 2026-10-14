import Foundation
import XCTest

@testable import KCC

final class AccountDeletionTests: XCTestCase {
    private final class FakeAuthRepository: AuthRepository, @unchecked Sendable {
        var authState: AuthState
        private var authoritativeUid: String?
        private(set) var signOutCount = 0
        var signOutError: Error?

        init(authState: AuthState) {
            self.authState = authState
            if case .signedIn(let uid, _) = authState {
                authoritativeUid = uid
            } else {
                authoritativeUid = nil
            }
        }

        func replaceAuthoritativeIdentityWithoutPublishing(uid: String?) {
            authoritativeUid = uid
        }

        func authStateUpdates() -> AsyncStream<AuthState> {
            let state = authState
            return AsyncStream { continuation in
                continuation.yield(state)
                continuation.finish()
            }
        }

        func signIn(with payload: AppleIDTokenPayload) async throws {}

        func signOut() throws {
            signOutCount += 1
            if let signOutError { throw signOutError }
        }

        func signOut(ifCurrentUidIs expectedUid: String) throws {
            guard authoritativeUid == expectedUid else { return }
            try signOut()
        }
    }

    private final class FakeRepository: AccountDeletionRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var storedReasons: [String?] = []
        var error: Error?
        var delayNanoseconds: UInt64 = 0

        var reasons: [String?] { lock.withLock { storedReasons } }

        func deleteAccount(reason: String?) async throws {
            lock.withLock { storedReasons.append(reason) }
            if delayNanoseconds > 0 { try await Task.sleep(nanoseconds: delayNanoseconds) }
            if let error { throw error }
        }
    }

    private final class FakeFunctions: AccountDeletionFunctionsCalling, @unchecked Sendable {
        private let lock = NSLock()
        private var storedCalls: [(String, [String: Any])] = []
        var response: Any? = ["requestId": "request", "status": "pending"]
        var error: Error?

        var calls: [(String, [String: Any])] { lock.withLock { storedCalls } }

        func call(_ name: String, payload: [String: Any]) async throws -> Any? {
            lock.withLock { storedCalls.append((name, payload)) }
            if let error { throw error }
            return response
        }
    }

    @MainActor
    func testSuccessfulDeletionIsTerminalAndSingleFlight() async throws {
        let repository = FakeRepository()
        repository.delayNanoseconds = 50_000_000
        let coordinator = AccountDeletionCoordinator(repository: repository)

        let first = Task { try await coordinator.delete(reason: nil) }
        await Task.yield()
        let duplicate = try await coordinator.delete(reason: "duplicate")
        let firstResult = try await first.value

        XCTAssertFalse(duplicate)
        XCTAssertTrue(firstResult)
        XCTAssertEqual(repository.reasons.count, 1)
        XCTAssertNil(repository.reasons[0])
        XCTAssertEqual(coordinator.status, .deleted)
        let terminalRetry = try await coordinator.delete()
        XCTAssertFalse(terminalRetry)
        XCTAssertEqual(repository.reasons.count, 1)
    }

    @MainActor
    func testConfiglessCoordinatorStaysIdle() async throws {
        let coordinator = AccountDeletionCoordinator(repository: nil)
        XCTAssertFalse(coordinator.isAvailable)
        let result = try await coordinator.delete()
        XCTAssertFalse(result)
        XCTAssertEqual(coordinator.status, .idle)
    }

    @MainActor
    func testCallableCodesMapToSafePresentationFailures() async throws {
        let expectations: [(KccFunctionsErrorCode, AccountDeletionFailure)] = [
            (.unauthenticated, .authenticationRequired),
            (.invalidArgument, .invalidRequest),
            (.unavailable, .temporarilyUnavailable),
            (.resourceExhausted, .temporarilyUnavailable),
            (.permissionDenied, .notPermitted),
            (.failedPrecondition, .notPermitted),
            (.notFound, .generic),
            (.alreadyExists, .generic),
            (.internalError, .generic),
            (.unknown, .generic),
        ]

        for (code, expected) in expectations {
            let repository = FakeRepository()
            repository.error = KccFunctionsError(code: code)
            let coordinator = AccountDeletionCoordinator(repository: repository)
            let result = try await coordinator.delete()
            XCTAssertFalse(result)
            XCTAssertEqual(coordinator.status, .failed(expected))
            coordinator.resetFailure()
            XCTAssertEqual(coordinator.status, .idle)
        }
    }

    @MainActor
    func testCancellationReturnsToIdleAndPropagates() async {
        let repository = FakeRepository()
        repository.delayNanoseconds = 5_000_000_000
        let coordinator = AccountDeletionCoordinator(repository: repository)
        let task = Task { try await coordinator.delete() }
        await Task.yield()
        task.cancel()

        do {
            _ = try await task.value
            XCTFail("Expected cancellation")
        } catch is CancellationError {
            XCTAssertEqual(coordinator.status, .idle)
        } catch {
            XCTFail("Unexpected error type")
        }
    }

    func testRepositoryOmitsBlankReasonAndValidatesAcknowledgement() async throws {
        let functions = FakeFunctions()
        let repository = FirebaseAccountDeletionRepository(functions: functions)

        try await repository.deleteAccount(reason: "  \n")
        XCTAssertEqual(functions.calls.count, 1)
        XCTAssertEqual(functions.calls[0].0, "account-deleteAccount")
        XCTAssertTrue(functions.calls[0].1.isEmpty)

        functions.response = ["requestId": "request", "status": "unexpected"]
        do {
            try await repository.deleteAccount(reason: nil)
            XCTFail("Expected malformed acknowledgement to fail")
        } catch let error as KccFunctionsError {
            XCTAssertEqual(error.code, .unknown)
        }
    }

    func testRepositoryTrimsReasonAndRejectsOversizeInputLocally() async throws {
        let functions = FakeFunctions()
        let repository = FirebaseAccountDeletionRepository(functions: functions)

        try await repository.deleteAccount(reason: "  reason  ")
        XCTAssertEqual(functions.calls[0].1["reason"] as? String, "reason")

        do {
            try await repository.deleteAccount(
                reason: String(repeating: "a", count: FirebaseAccountDeletionRepository.maximumReasonLength + 1)
            )
            XCTFail("Expected oversized reason to fail")
        } catch let error as KccFunctionsError {
            XCTAssertEqual(error.code, .invalidArgument)
        }
        XCTAssertEqual(functions.calls.count, 1)
    }

    func testRepositoryMatchesBackendUTF16ReasonBoundary() async throws {
        let functions = FakeFunctions()
        let repository = FirebaseAccountDeletionRepository(functions: functions)

        // Emoji are one Swift Character but two UTF-16 code units, matching
        // JavaScript/Zod string-length validation in the callable backend.
        let accepted = String(repeating: "😀", count: 250)
        try await repository.deleteAccount(reason: accepted)
        XCTAssertEqual(functions.calls[0].1["reason"] as? String, accepted)

        do {
            try await repository.deleteAccount(reason: accepted + "a")
            XCTFail("Expected a 501-code-unit reason to fail locally")
        } catch let error as KccFunctionsError {
            XCTAssertEqual(error.code, .invalidArgument)
        }
        XCTAssertEqual(functions.calls.count, 1)
    }

    @MainActor
    func testDeletionCompletionCannotSignOutReplacementIdentity() {
        let repository = FakeAuthRepository(
            authState: .signedIn(uid: "replacement", displayName: nil)
        )
        var clearedUids: [String] = []
        let session = AuthSession(repository: repository) { clearedUids.append($0); return true }

        XCTAssertTrue(session.signOut(ifSignedInAs: "deletion-origin"))
        XCTAssertEqual(repository.signOutCount, 0)
        XCTAssertTrue(clearedUids.isEmpty)

        XCTAssertTrue(session.signOut(ifSignedInAs: "replacement"))
        XCTAssertEqual(repository.signOutCount, 1)
        XCTAssertEqual(clearedUids, ["replacement"])
    }

    @MainActor
    func testSignOutFailureIsReportedAndCanBeRetriedForTheSameIdentity() {
        struct ExpectedFailure: Error {}
        let repository = FakeAuthRepository(
            authState: .signedIn(uid: "deletion-origin", displayName: nil)
        )
        repository.signOutError = ExpectedFailure()
        let session = AuthSession(repository: repository, clearSavedPlaces: { _ in true })

        XCTAssertFalse(session.signOut(ifSignedInAs: "deletion-origin"))
        XCTAssertEqual(repository.signOutCount, 1)

        repository.signOutError = nil
        XCTAssertTrue(session.signOut(ifSignedInAs: "deletion-origin"))
        XCTAssertEqual(repository.signOutCount, 2)
    }

    @MainActor
    func testDeferredSignOutCannotAffectReplacementIdentityEvenAfterFailure() {
        struct ExpectedFailure: Error {}
        let repository = FakeAuthRepository(
            authState: .signedIn(uid: "replacement", displayName: nil)
        )
        repository.signOutError = ExpectedFailure()
        var clearedUids: [String] = []
        let session = AuthSession(repository: repository) { clearedUids.append($0); return true }

        // A cancelled/deferred task from the deleted identity is treated as
        // complete without touching the replacement account's credentials.
        XCTAssertTrue(session.signOut(ifSignedInAs: "deleted-origin"))
        XCTAssertEqual(repository.signOutCount, 0)
        XCTAssertTrue(clearedUids.isEmpty)
    }

    @MainActor
    func testDeferredSignOutReadsAuthoritativeIdentityBeforeObserverConsumesReplacement() {
        let repository = FakeAuthRepository(
            authState: .signedIn(uid: "deletion-origin", displayName: nil)
        )
        var clearedUids: [String] = []
        let session = AuthSession(repository: repository) { clearedUids.append($0); return true }

        // Firebase has already switched accounts, but the listener-backed
        // repository/session state has not published or consumed that update.
        repository.replaceAuthoritativeIdentityWithoutPublishing(uid: "replacement")

        XCTAssertEqual(session.state, .signedIn(uid: "deletion-origin", displayName: nil))
        XCTAssertTrue(session.signOut(ifSignedInAs: "deletion-origin"))
        XCTAssertEqual(repository.signOutCount, 0)
        XCTAssertEqual(clearedUids, ["deletion-origin"])
    }

    @MainActor
    func testSavedPlacesCleanupFailureBlocksSignOutAndCanBeRetried() {
        let repository = FakeAuthRepository(
            authState: .signedIn(uid: "member", displayName: nil)
        )
        var cleanupSucceeds = false
        var cleanupCount = 0
        let session = AuthSession(repository: repository) { uid in
            XCTAssertEqual(uid, "member")
            cleanupCount += 1
            return cleanupSucceeds
        }

        XCTAssertFalse(session.signOut())
        XCTAssertEqual(cleanupCount, 1)
        XCTAssertEqual(repository.signOutCount, 0)

        cleanupSucceeds = true
        XCTAssertTrue(session.signOut())
        XCTAssertEqual(cleanupCount, 2)
        XCTAssertEqual(repository.signOutCount, 1)
    }
}
