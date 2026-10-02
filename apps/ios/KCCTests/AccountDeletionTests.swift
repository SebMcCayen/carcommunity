import Foundation
import XCTest

@testable import KCC

final class AccountDeletionTests: XCTestCase {
    private final class FakeAuthRepository: AuthRepository, @unchecked Sendable {
        var authState: AuthState
        private(set) var signOutCount = 0

        init(authState: AuthState) {
            self.authState = authState
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

    @MainActor
    func testDeletionCompletionCannotSignOutReplacementIdentity() {
        let repository = FakeAuthRepository(
            authState: .signedIn(uid: "replacement", displayName: nil)
        )
        let session = AuthSession(repository: repository)

        session.signOut(ifSignedInAs: "deletion-origin")
        XCTAssertEqual(repository.signOutCount, 0)

        session.signOut(ifSignedInAs: "replacement")
        XCTAssertEqual(repository.signOutCount, 1)
    }
}
