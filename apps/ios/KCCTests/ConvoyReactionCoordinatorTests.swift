import Foundation
import XCTest
@testable import KCC

@MainActor
final class ConvoyReactionCoordinatorTests: XCTestCase {
    func testSyncObservesCurrentConvoyAndCancelsPreviousListener() async {
        let repository = ConvoyReactionRepositoryFake()
        let coordinator = ConvoyReactionCoordinator(
            repository: repository,
            nowDate: { Date(timeIntervalSince1970: 100) }
        )

        coordinator.sync(convoyId: "first")
        XCTAssertEqual(repository.observedConvoys, ["first"])
        repository.emit(event(id: "old"), to: "first")
        await waitUntil { coordinator.incomingReaction?.id == "old" }
        XCTAssertEqual(coordinator.incomingReaction?.id, "old")

        coordinator.sync(convoyId: "second")
        XCTAssertNil(coordinator.incomingReaction)
        XCTAssertEqual(repository.observedConvoys, ["first", "second"])
        await waitUntil { repository.terminationCount(for: "first") == 1 }
        XCTAssertEqual(repository.terminationCount(for: "first"), 1)

        repository.emit(event(id: "new"), to: "second")
        await waitUntil { coordinator.incomingReaction?.id == "new" }
        XCTAssertEqual(coordinator.incomingReaction?.id, "new")
        coordinator.sync(convoyId: nil)
        await waitUntil { repository.terminationCount(for: "second") == 1 }
        XCTAssertEqual(repository.terminationCount(for: "second"), 1)
    }

    func testSuccessfulSendStartsCooldownAndUsesIdempotencyKey() async {
        let clock = ReactionTestClock(milliseconds: 1_000)
        let repository = ConvoyReactionRepositoryFake()
        repository.nextSendResult = .sent
        let coordinator = ConvoyReactionCoordinator(
            repository: repository,
            nowMilliseconds: { clock.milliseconds },
            makeClientId: { "client-id" }
        )
        coordinator.sync(convoyId: "convoy-1")

        await coordinator.send(.hello)

        XCTAssertEqual(repository.sends, [ReactionSend(
            convoyId: "convoy-1",
            kind: .hello,
            clientId: "client-id"
        )])
        XCTAssertEqual(
            coordinator.remainingMilliseconds(for: .hello, nowMilliseconds: 1_000),
            15_000
        )
        XCTAssertEqual(
            coordinator.remainingMilliseconds(for: .police, nowMilliseconds: 1_000),
            0
        )
    }

    func testFailedSendClearsOptimisticCooldownForRetry() async {
        let repository = ConvoyReactionRepositoryFake()
        repository.nextSendResult = .failed
        let coordinator = ConvoyReactionCoordinator(
            repository: repository,
            nowMilliseconds: { 1_000 }
        )
        coordinator.sync(convoyId: "convoy-1")

        await coordinator.send(.police)

        XCTAssertEqual(
            coordinator.remainingMilliseconds(for: .police, nowMilliseconds: 1_000),
            0
        )
    }

    func testServerRateLimitReplacesClientCooldown() async {
        let clock = ReactionTestClock(milliseconds: 1_000)
        let repository = ConvoyReactionRepositoryFake()
        repository.nextSendResult = .rateLimited(retryAfterMilliseconds: 4_000)
        let coordinator = ConvoyReactionCoordinator(
            repository: repository,
            nowMilliseconds: { clock.milliseconds }
        )
        coordinator.sync(convoyId: "convoy-1")
        clock.milliseconds = 2_000

        await coordinator.send(.police)

        XCTAssertEqual(
            coordinator.remainingMilliseconds(for: .police, nowMilliseconds: 2_000),
            4_000
        )
    }

    func testUnknownOrRepeatedReactionIdCanBeDismissedSafely() async {
        let repository = ConvoyReactionRepositoryFake()
        let coordinator = ConvoyReactionCoordinator(repository: repository)
        coordinator.sync(convoyId: "convoy-1")
        repository.emit(event(id: "one"), to: "convoy-1")
        await waitUntil { coordinator.incomingReaction?.id == "one" }

        coordinator.dismissIncoming(id: "other")
        XCTAssertEqual(coordinator.incomingReaction?.id, "one")
        coordinator.dismissIncoming(id: "one")
        XCTAssertNil(coordinator.incomingReaction)
    }

    private func waitUntil(
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ predicate: () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate() { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out waiting for condition", file: file, line: line)
    }

    private func event(id: String) -> ConvoyReactionEvent {
        ConvoyReactionEvent(
            id: id,
            kind: .hello,
            senderUid: "sender",
            senderName: "Member",
            createdAt: Date(timeIntervalSince1970: 101)
        )
    }
}

private struct ReactionSend: Equatable {
    let convoyId: String
    let kind: ConvoyReactionKind
    let clientId: String
}

private final class ReactionTestClock: @unchecked Sendable {
    var milliseconds: Int64
    init(milliseconds: Int64) { self.milliseconds = milliseconds }
}

private final class ConvoyReactionRepositoryFake: ConvoyReactionRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [String: AsyncStream<ConvoyReactionEvent>.Continuation] = [:]
    private var terminations: [String: Int] = [:]
    private var recordedConvoys: [String] = []
    private var recordedSends: [ReactionSend] = []
    var nextSendResult: ConvoyReactionSendResult = .sent

    var observedConvoys: [String] { lock.withLock { recordedConvoys } }
    var sends: [ReactionSend] { lock.withLock { recordedSends } }

    func terminationCount(for convoyId: String) -> Int {
        lock.withLock { terminations[convoyId, default: 0] }
    }

    func send(
        convoyId: String,
        kind: ConvoyReactionKind,
        clientId: String
    ) async -> ConvoyReactionSendResult {
        lock.withLock {
            recordedSends.append(ReactionSend(
                convoyId: convoyId,
                kind: kind,
                clientId: clientId
            ))
            return nextSendResult
        }
    }

    func reactions(
        convoyId: String,
        since: Date
    ) -> AsyncStream<ConvoyReactionEvent> {
        AsyncStream { continuation in
            lock.withLock {
                recordedConvoys.append(convoyId)
                continuations[convoyId] = continuation
            }
            continuation.onTermination = { [weak self] _ in
                self?.lock.withLock {
                    self?.terminations[convoyId, default: 0] += 1
                    self?.continuations.removeValue(forKey: convoyId)
                }
            }
        }
    }

    func emit(_ event: ConvoyReactionEvent, to convoyId: String) {
        let continuation = lock.withLock { continuations[convoyId] }
        continuation?.yield(event)
    }
}
