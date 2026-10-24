import Foundation
import Testing
@testable import KCC

@Suite("StoreKit subscription flow")
struct SubscriptionCoordinatorTests {
    @Test("account binding matches the backend UUID derivation")
    func accountToken() {
        #expect(SubscriptionAccountToken.forUID("member-apple-1")?.uuidString.lowercased() ==
            "b9cb9968-8740-5985-9a95-c001f758cce6")
    }

    @Test("opening membership reconciles and finishes a verified Supporter entitlement")
    @MainActor
    func reconcileSupporter() async {
        let transaction = StoreSignedTransaction(
            id: 7,
            productID: supporterMonthlyProductID,
            signedTransaction: "signed-supporter"
        )
        let store = FakeSubscriptionStore(entitlements: [transaction])
        let verifier = FakeAppleVerifier(result: .success(SubscriptionVerificationResult(
            entitlement: "member_monthly",
            status: "active",
            tier: .supporter
        )))
        let coordinator = SubscriptionCoordinator(uid: "member", store: store, verifier: verifier)

        await coordinator.start()

        #expect(coordinator.currentTier == .supporter)
        #expect(coordinator.status == .success(.supporter))
        let finished = await store.finishedIDs
        #expect(finished == [7])
    }

    @Test("a backend failure leaves the transaction unfinished for redelivery")
    @MainActor
    func verificationFailureDoesNotFinish() async {
        let transaction = StoreSignedTransaction(
            id: 9,
            productID: plusMonthlyProductID,
            signedTransaction: "signed-plus"
        )
        let store = FakeSubscriptionStore(
            purchaseResult: .purchased(transaction)
        )
        let verifier = FakeAppleVerifier(result: .failure(TestError.failed))
        let coordinator = SubscriptionCoordinator(uid: "member", store: store, verifier: verifier)

        await coordinator.start()
        await coordinator.purchase(plusMonthlyProductID)

        #expect(coordinator.status == .failed(.verification))
        let finished = await store.finishedIDs
        #expect(finished.isEmpty)
    }

    @Test("a pending StoreKit payment never reaches verification")
    @MainActor
    func pendingPurchase() async {
        let store = FakeSubscriptionStore(purchaseResult: .pending)
        let verifier = FakeAppleVerifier(result: .failure(TestError.failed))
        let coordinator = SubscriptionCoordinator(uid: "member", store: store, verifier: verifier)

        await coordinator.start()
        await coordinator.purchase(plusMonthlyProductID)

        #expect(coordinator.status == .pending)
        let calls = await verifier.calls
        #expect(calls == 0)
    }
    @Test("approval is delivered without opening membership")
    @MainActor
    func delayedApproval() async throws {
        let transaction = StoreSignedTransaction(id: 31, productID: plusMonthlyProductID,
            signedTransaction: "delayed", appAccountToken: SubscriptionAccountToken.forUID("member"))
        let store = FakeSubscriptionStore()
        let verifier = FakeAppleVerifier(result: .success(SubscriptionVerificationResult(
            entitlement: "member_monthly", status: "active", tier: .plus)))
        let observer = SubscriptionTransactionObserver(store: store, verifier: verifier)
        let task = Task { await observer.run(uid: "member") }
        defer { task.cancel() }
        await store.emit(transaction)
        for _ in 0..<100 {
            if await store.finishedIDs.contains(31) { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let finished = await store.finishedIDs
        #expect(finished == [31])
    }

    @Test("unfinished verification retries automatically after backend recovery")
    @MainActor
    func automaticRetry() async throws {
        let transaction = StoreSignedTransaction(id: 32, productID: plusMonthlyProductID,
            signedTransaction: "retry", appAccountToken: SubscriptionAccountToken.forUID("member"))
        let store = FakeSubscriptionStore(entitlements: [transaction])
        let verifier = RecoveringAppleVerifier()
        let observer = SubscriptionTransactionObserver(store: store, verifier: verifier,
                                                       retryDelay: .milliseconds(10))
        let task = Task { await observer.run(uid: "member") }
        defer { task.cancel() }
        for _ in 0..<100 {
            if await store.finishedIDs.contains(32) { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let finished = await store.finishedIDs
        let calls = await verifier.calls
        #expect(finished == [32])
        #expect(calls == 2)
    }

    @Test("a different app account's transaction is never submitted or finished")
    @MainActor
    func accountFence() async throws {
        let transaction = StoreSignedTransaction(id: 33, productID: plusMonthlyProductID,
            signedTransaction: "other-account", appAccountToken: SubscriptionAccountToken.forUID("A"))
        let store = FakeSubscriptionStore(entitlements: [transaction])
        let verifier = FakeAppleVerifier(result: .failure(TestError.failed))
        let observer = SubscriptionTransactionObserver(store: store, verifier: verifier)
        let task = Task { await observer.run(uid: "B") }
        await store.emit(transaction)
        try await Task.sleep(for: .milliseconds(50))
        task.cancel()
        await task.value
        let calls = await verifier.calls
        let finished = await store.finishedIDs
        #expect(calls == 0)
        #expect(finished.isEmpty)
    }

}

private actor FakeSubscriptionStore: SubscriptionStore {
    private let availableProducts: [StoreSubscriptionProduct]
    private let purchaseResult: StorePurchaseResult
    private let entitlements: [StoreSignedTransaction]
    private let updates: AsyncStream<StoreSignedTransaction>
    private let continuation: AsyncStream<StoreSignedTransaction>.Continuation
    private(set) var finishedIDs: [UInt64] = []

    init(
        products: [StoreSubscriptionProduct] = [
            StoreSubscriptionProduct(id: plusMonthlyProductID, displayPrice: "SEK 39"),
            StoreSubscriptionProduct(id: supporterMonthlyProductID, displayPrice: "SEK 119"),
        ],
        purchaseResult: StorePurchaseResult = .cancelled,
        entitlements: [StoreSignedTransaction] = []
    ) {
        (updates, continuation) = AsyncStream.makeStream()
        availableProducts = products
        self.purchaseResult = purchaseResult
        self.entitlements = entitlements
    }

    func products() async throws -> [StoreSubscriptionProduct] { availableProducts }
    func purchase(productID: String, appAccountToken: UUID) async throws -> StorePurchaseResult {
        purchaseResult
    }
    func currentEntitlements() async -> [StoreSignedTransaction] { entitlements }
    func transactionUpdates() async -> AsyncStream<StoreSignedTransaction> { updates }
    func unfinishedTransactions() async -> [StoreSignedTransaction] { entitlements }
    func emit(_ transaction: StoreSignedTransaction) { continuation.yield(transaction) }
    func synchronize() async throws {}
    func finish(transactionID: UInt64) async { finishedIDs.append(transactionID) }
}

private actor FakeAppleVerifier: AppleSubscriptionVerifying {
    private let result: Result<SubscriptionVerificationResult, Error>
    private(set) var calls = 0

    init(result: Result<SubscriptionVerificationResult, Error>) {
        self.result = result
    }

    func verify(signedTransaction: String, expectedUID: String) async throws -> SubscriptionVerificationResult {
        calls += 1
        return try result.get()
    }
}

private enum TestError: Error {
    case failed
}

private actor RecoveringAppleVerifier: AppleSubscriptionVerifying {
    private(set) var calls = 0
    func verify(signedTransaction: String, expectedUID: String) async throws -> SubscriptionVerificationResult {
        calls += 1
        if calls == 1 { throw TestError.failed }
        return SubscriptionVerificationResult(entitlement: "member_monthly", status: "active", tier: .plus)
    }
}
