import Foundation
import Testing
@testable import KCC

@Suite("StoreKit subscription flow")
struct SubscriptionCoordinatorTests {
    @Test("only a future Supporter-to-Plus renewal preference is a scheduled downgrade")
    func scheduledChangePolicy() {
        let now = Date()
        let future = now.addingTimeInterval(3600)
        #expect(ScheduledSubscriptionChange.downgrade(currentProductID: supporterMonthlyProductID,
            nextProductID: plusMonthlyProductID, willAutoRenew: true, effectiveAt: future, now: now) != nil)
        for target in [supporterMonthlyProductID, "unknown"] {
            #expect(ScheduledSubscriptionChange.downgrade(currentProductID: supporterMonthlyProductID,
                nextProductID: target, willAutoRenew: true, effectiveAt: future, now: now) == nil)
        }
        #expect(ScheduledSubscriptionChange.downgrade(currentProductID: supporterMonthlyProductID,
            nextProductID: plusMonthlyProductID, willAutoRenew: false, effectiveAt: future, now: now) == nil)
        #expect(ScheduledSubscriptionChange.downgrade(currentProductID: plusMonthlyProductID,
            nextProductID: plusMonthlyProductID, willAutoRenew: true, effectiveAt: future, now: now) == nil)
        #expect(ScheduledSubscriptionChange.downgrade(currentProductID: supporterMonthlyProductID,
            nextProductID: plusMonthlyProductID, willAutoRenew: true, effectiveAt: now, now: now) == nil)
    }

    @Test("confirmed downgrade keeps Supporter and blocks another purchase of the scheduled plan")
    @MainActor
    func scheduledDowngrade() async {
        let transaction = StoreSignedTransaction(id: 70, productID: supporterMonthlyProductID,
            signedTransaction: "owned")
        let store = FakeSubscriptionStore(purchaseResult: .purchased(transaction), entitlements: [transaction])
        let verifier = FakeAppleVerifier(result: .success(SubscriptionVerificationResult(
            entitlement: "member_monthly", status: "active", tier: .supporter)))
        let coordinator = SubscriptionCoordinator(uid: "member", store: store, verifier: verifier)
        await coordinator.start()
        let change = ScheduledSubscriptionChange(productID: plusMonthlyProductID,
            effectiveAt: Date().addingTimeInterval(3600))
        await store.setScheduledChange(.success(change))

        await coordinator.purchase(plusMonthlyProductID)

        #expect(coordinator.currentTier == .supporter)
        #expect(coordinator.upcomingChange == change)
        #expect(coordinator.isChangeScheduled(to: plusMonthlyProductID))
        #expect(coordinator.status == .success(.supporter, operation: .planChange))
        #expect(coordinator.status.messageKey == "subscription.statusChangeScheduled")
        await coordinator.purchase(plusMonthlyProductID)
        #expect(await store.purchaseCalls == 1)

        // A temporary renewal lookup failure does not erase a confirmed change.
        await store.setScheduledChange(.failure(TestError.failed))
        await coordinator.reloadAndReconcile()
        #expect(coordinator.upcomingChange == change)
        #expect(coordinator.renewalInformationUnavailable)

        // Apple cancellation of the downgrade removes its display and button lock.
        await store.setScheduledChange(.success(nil))
        await coordinator.reloadAndReconcile()
        #expect(coordinator.upcomingChange == nil)
        #expect(!coordinator.renewalInformationUnavailable)
        #expect(!coordinator.isChangeScheduled(to: plusMonthlyProductID))

        // The real renewal changes access only after the backend verifies Plus.
        await store.setScheduledChange(.success(change))
        await coordinator.reloadAndReconcile()
        await store.setEntitlements([StoreSignedTransaction(id: 72, productID: plusMonthlyProductID,
            signedTransaction: "renewed-plus")])
        await verifier.setResult(.success(SubscriptionVerificationResult(
            entitlement: "member_monthly", status: "active", tier: .plus)))
        await coordinator.reloadAndReconcile()
        #expect(coordinator.currentTier == .plus)
        #expect(coordinator.upcomingChange == nil)
    }

    @Test("catalogue refresh failure keeps displayed products and entitlement but pauses checkout")
    @MainActor
    func retainedCatalogueAfterFailure() async {
        let transaction = StoreSignedTransaction(id: 71, productID: supporterMonthlyProductID,
            signedTransaction: "owned")
        let store = FakeSubscriptionStore(entitlements: [transaction])
        let verifier = FakeAppleVerifier(result: .success(SubscriptionVerificationResult(
            entitlement: "member_monthly", status: "active", tier: .supporter)))
        let coordinator = SubscriptionCoordinator(uid: "member", store: store, verifier: verifier)
        await coordinator.start()
        let displayedProducts = coordinator.products
        await store.setCatalogueUnavailable(true)

        await coordinator.reloadAndReconcile()

        #expect(coordinator.products == displayedProducts)
        #expect(coordinator.currentTier == .supporter)
        #expect(coordinator.catalogueUnavailable)
        #expect(!coordinator.canPurchase)
        await coordinator.purchase(plusMonthlyProductID)
        #expect(await store.purchaseCalls == 0)

        await store.setCatalogueUnavailable(false)
        await coordinator.reloadAndReconcile()
        #expect(!coordinator.catalogueUnavailable)
        #expect(coordinator.canPurchase)

        // A catalogue outage discovered by the mandatory pre-checkout query
        // must also retain the cards and pause later checkout attempts.
        await store.setPurchaseFailure(SubscriptionStoreFailure.catalogueUnavailable)
        await coordinator.purchase(plusMonthlyProductID)
        #expect(coordinator.products == displayedProducts)
        #expect(coordinator.catalogueUnavailable)
        #expect(!coordinator.canPurchase)
        #expect(coordinator.currentTier == .supporter)
    }
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
        #expect(coordinator.status == .success(.supporter, operation: .verification))
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

    @Test("restore reconciles existing membership even when the catalogue is unavailable")
    @MainActor
    func catalogueFailureDoesNotBlockReconciliation() async {
        let transaction = StoreSignedTransaction(id: 41, productID: plusMonthlyProductID,
                                                signedTransaction: "already-owned")
        let store = FakeSubscriptionStore(entitlements: [transaction], catalogueUnavailable: true)
        let verifier = FakeAppleVerifier(result: .success(SubscriptionVerificationResult(
            entitlement: "member_monthly", status: "active", tier: .plus)))
        let coordinator = SubscriptionCoordinator(uid: "member", store: store, verifier: verifier)
        await coordinator.start()
        #expect(coordinator.currentTier == .plus)
        #expect(coordinator.products.isEmpty)
        #expect(!coordinator.canPurchase)
        let finished = await store.finishedIDs
        #expect(finished == [41])
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

    @Test("purchase, restore and verification have distinct confirmations", arguments: [SubscriptionTier.plus, .supporter])
    @MainActor
    func operationFeedback(tier: SubscriptionTier) async {
        let transaction = StoreSignedTransaction(id: 42,
            productID: tier == .plus ? plusMonthlyProductID : supporterMonthlyProductID,
            signedTransaction: "owned")
        let store = FakeSubscriptionStore(purchaseResult: .purchased(transaction), entitlements: [transaction])
        let verifier = FakeAppleVerifier(result: .success(SubscriptionVerificationResult(
            entitlement: "member_monthly", status: "active", tier: tier)))
        let coordinator = SubscriptionCoordinator(uid: "member", store: store, verifier: verifier)

        await coordinator.start()
        #expect(coordinator.status == .success(tier, operation: .verification))
        #expect(coordinator.status.messageKey == "subscription.statusVerified")

        await coordinator.purchase(transaction.productID)
        #expect(coordinator.status == .success(tier, operation: .purchase))
        #expect(coordinator.status.messageKey == "subscription.statusSuccess")

        await coordinator.restore()
        #expect(coordinator.status == .success(tier, operation: .restore))
        #expect(coordinator.status.messageKey == "subscription.statusRestored")
        #expect(coordinator.currentTier == tier)
    }

    @Test("failed Apple synchronization preserves membership and retry only verifies")
    @MainActor
    func restoreFailureThenRetry() async {
        let transaction = StoreSignedTransaction(id: 43, productID: supporterMonthlyProductID,
            signedTransaction: "owned")
        let store = FakeSubscriptionStore(entitlements: [transaction], synchronizationFails: true)
        let verifier = FakeAppleVerifier(result: .success(SubscriptionVerificationResult(
            entitlement: "member_monthly", status: "active", tier: .supporter)))
        let coordinator = SubscriptionCoordinator(uid: "member", store: store, verifier: verifier)
        await coordinator.start()

        await coordinator.restore()
        #expect(coordinator.status == .failed(.restore))
        #expect(coordinator.status.messageKey == "subscription.errorRestore")
        #expect(coordinator.currentTier == .supporter)
        #expect(await verifier.calls == 1)

        await coordinator.reloadAndReconcile()
        #expect(coordinator.status == .success(.supporter, operation: .verification))
        #expect(coordinator.status.messageKey == "subscription.statusVerified")
        #expect(await store.synchronizationCalls == 1)
        #expect(await verifier.calls == 2)
    }

    @Test("restore verification failure has restore copy and leaves the transaction unfinished")
    @MainActor
    func restoreVerificationFailure() async {
        let transaction = StoreSignedTransaction(id: 44, productID: plusMonthlyProductID,
            signedTransaction: "owned")
        let store = FakeSubscriptionStore(entitlements: [transaction])
        let verifier = FakeAppleVerifier(result: .failure(TestError.failed))
        let coordinator = SubscriptionCoordinator(uid: "member", store: store, verifier: verifier)

        await coordinator.restore()

        #expect(coordinator.status == .failed(.restoreVerification))
        #expect(coordinator.status.messageKey == "subscription.errorRestoreVerification")
        #expect(await store.finishedIDs.isEmpty)
        await coordinator.reloadAndReconcile()
        #expect(coordinator.status == .failed(.membershipVerification))
        #expect(coordinator.status.messageKey == "subscription.errorMembershipVerification")
    }

    @Test("restore with no active purchases never reports success or verifies")
    @MainActor
    func nothingToRestore() async {
        let store = FakeSubscriptionStore()
        let verifier = FakeAppleVerifier(result: .failure(TestError.failed))
        let coordinator = SubscriptionCoordinator(uid: "member", store: store, verifier: verifier)

        await coordinator.restore()

        #expect(coordinator.status == .failed(.noPurchasesToRestore))
        #expect(coordinator.status.messageKey == "subscription.errorNoPurchasesToRestore")
        #expect(await verifier.calls == 0)
    }

    @Test("a restored transaction without verified access never reports restoration success")
    @MainActor
    func inactiveRestoration() async {
        let store = FakeSubscriptionStore(entitlements: [StoreSignedTransaction(
            id: 45, productID: plusMonthlyProductID, signedTransaction: "inactive")])
        let verifier = FakeAppleVerifier(result: .success(SubscriptionVerificationResult(
            entitlement: "none", status: "expired", tier: .plus)))
        let coordinator = SubscriptionCoordinator(uid: "member", store: store, verifier: verifier)

        await coordinator.restore()

        #expect(coordinator.status == .failed(.noPurchasesToRestore))
        #expect(coordinator.currentTier == nil)
        #expect(SubscriptionFlowStatus.restoring.isBusy)
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
        for _ in 0..<500 {
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
        for _ in 0..<500 {
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

    @Test("an expired unfinished period cannot revoke a newer active membership")
    @MainActor
    func historicalPeriod() async throws {
        let token = SubscriptionAccountToken.forUID("member")
        let old = StoreSignedTransaction(id: 34, productID: plusMonthlyProductID,
            signedTransaction: "expired", appAccountToken: token, expiresAt: .distantPast)
        let current = StoreSignedTransaction(id: 35, productID: supporterMonthlyProductID,
            signedTransaction: "current", appAccountToken: token, expiresAt: .distantFuture)
        let store = FakeSubscriptionStore(entitlements: [current], unfinished: [old])
        let verifier = FakeAppleVerifier(result: .success(SubscriptionVerificationResult(
            entitlement: "member_monthly", status: "active", tier: .supporter)))
        let observer = SubscriptionTransactionObserver(store: store, verifier: verifier)
        let task = Task { await observer.run(uid: "member") }
        defer { task.cancel() }
        for _ in 0..<500 {
            if await store.finishedIDs.contains(34) { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let submitted = await verifier.signedTransactions
        let finished = await store.finishedIDs
        #expect(submitted == ["current"])
        #expect(finished == [35, 34])
    }

    @Test("a verification response after session cancellation cannot finish its transaction")
    @MainActor
    func cancelledVerification() async throws {
        let transaction = StoreSignedTransaction(id: 36, productID: plusMonthlyProductID,
            signedTransaction: "in-flight", appAccountToken: SubscriptionAccountToken.forUID("member"))
        let store = FakeSubscriptionStore(unfinished: [transaction])
        let verifier = HoldingAppleVerifier()
        let observer = SubscriptionTransactionObserver(store: store, verifier: verifier)
        let task = Task { await observer.run(uid: "member") }
        for _ in 0..<500 {
            if await verifier.entered { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let entered = await verifier.entered
        #expect(entered)
        task.cancel()
        await verifier.complete()
        await task.value
        let finished = await store.finishedIDs
        #expect(finished.isEmpty)
    }

    @Test("a failed in-app verification is recovered even without a StoreKit update event")
    @MainActor
    func inAppPurchaseRecovery() async throws {
        let transaction = StoreSignedTransaction(id: 37, productID: plusMonthlyProductID,
            signedTransaction: "in-app", appAccountToken: SubscriptionAccountToken.forUID("member"))
        let store = FakeSubscriptionStore(purchaseResult: .purchased(transaction))
        let verifier = FakeAppleVerifier(result: .success(SubscriptionVerificationResult(
            entitlement: "member_monthly", status: "active", tier: .plus)))
        let observer = SubscriptionTransactionObserver(store: store, verifier: verifier,
                                                       retryDelay: .milliseconds(10))
        let task = Task { await observer.run(uid: "member") }
        defer { task.cancel() }
        for _ in 0..<500 {
            if await store.unfinishedReads > 0 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let membership = SubscriptionCoordinator(uid: "member", store: store,
            verifier: FakeAppleVerifier(result: .failure(TestError.failed)))
        await membership.start()
        await membership.purchase(plusMonthlyProductID)
        // No transaction update is emitted. The root observer must discover
        // the unfinished transaction independently of the membership screen.
        for _ in 0..<500 {
            if await store.finishedIDs.contains(37) { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let submitted = await verifier.signedTransactions
        let finished = await store.finishedIDs
        #expect(submitted == ["in-app"])
        #expect(finished == [37])
    }

}

private actor FakeSubscriptionStore: SubscriptionStore {
    private let availableProducts: [StoreSubscriptionProduct]
    private let purchaseResult: StorePurchaseResult
    private var entitlements: [StoreSignedTransaction]
    private var catalogueUnavailable: Bool
    private var scheduledResult: Result<ScheduledSubscriptionChange?, Error> = .success(nil)
    private(set) var purchaseCalls = 0
    private var purchaseFailure: Error?
    private let synchronizationFails: Bool
    private(set) var synchronizationCalls = 0
    private var unfinished: [StoreSignedTransaction]
    private(set) var unfinishedReads = 0
    private let updates: AsyncStream<StoreSignedTransaction>
    private let continuation: AsyncStream<StoreSignedTransaction>.Continuation
    private(set) var finishedIDs: [UInt64] = []

    init(
        products: [StoreSubscriptionProduct] = [
            StoreSubscriptionProduct(id: plusMonthlyProductID, displayPrice: "SEK 39"),
            StoreSubscriptionProduct(id: supporterMonthlyProductID, displayPrice: "SEK 119"),
        ],
        purchaseResult: StorePurchaseResult = .cancelled,
        entitlements: [StoreSignedTransaction] = [],
        unfinished: [StoreSignedTransaction]? = nil,
        catalogueUnavailable: Bool = false,
        synchronizationFails: Bool = false
    ) {
        (updates, continuation) = AsyncStream.makeStream()
        availableProducts = products
        self.purchaseResult = purchaseResult
        self.entitlements = entitlements
        self.catalogueUnavailable = catalogueUnavailable
        self.synchronizationFails = synchronizationFails
        self.unfinished = unfinished ?? entitlements
    }

    func products() async throws -> [StoreSubscriptionProduct] {
        if catalogueUnavailable { throw TestError.failed }
        return availableProducts
    }
    func purchase(productID: String, appAccountToken: UUID) async throws -> StorePurchaseResult {
        purchaseCalls += 1
        if let purchaseFailure { throw purchaseFailure }
        if case .purchased(let transaction) = purchaseResult { unfinished.append(transaction) }
        return purchaseResult
    }
    func currentEntitlements() async -> [StoreSignedTransaction] { entitlements }
    func setEntitlements(_ value: [StoreSignedTransaction]) { entitlements = value }
    func setCatalogueUnavailable(_ value: Bool) { catalogueUnavailable = value }
    func setPurchaseFailure(_ value: Error?) { purchaseFailure = value }
    func setScheduledChange(_ result: Result<ScheduledSubscriptionChange?, Error>) { scheduledResult = result }
    func scheduledChange(for transaction: StoreSignedTransaction, appAccountToken: UUID) async throws -> ScheduledSubscriptionChange? {
        try scheduledResult.get()
    }
    func transactionUpdates() async -> AsyncStream<StoreSignedTransaction> { updates }
    func unfinishedTransactions() async -> [StoreSignedTransaction] {
        unfinishedReads += 1
        return unfinished.filter { !finishedIDs.contains($0.id) }
    }
    func emit(_ transaction: StoreSignedTransaction) { continuation.yield(transaction) }
    func synchronize() async throws {
        synchronizationCalls += 1
        if synchronizationFails { throw TestError.failed }
    }
    func finish(transactionID: UInt64) async { finishedIDs.append(transactionID) }
}

private actor FakeAppleVerifier: AppleSubscriptionVerifying {
    private var result: Result<SubscriptionVerificationResult, Error>
    private(set) var calls = 0
    private(set) var signedTransactions: [String] = []

    init(result: Result<SubscriptionVerificationResult, Error>) {
        self.result = result
    }
    func setResult(_ value: Result<SubscriptionVerificationResult, Error>) { result = value }

    func verify(signedTransaction: String, expectedUID: String) async throws -> SubscriptionVerificationResult {
        calls += 1
        signedTransactions.append(signedTransaction)
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

private actor HoldingAppleVerifier: AppleSubscriptionVerifying {
    private(set) var entered = false
    private var continuation: CheckedContinuation<SubscriptionVerificationResult, Never>?
    func verify(signedTransaction: String, expectedUID: String) async throws -> SubscriptionVerificationResult {
        entered = true
        return await withCheckedContinuation { continuation = $0 }
    }
    func complete() {
        continuation?.resume(returning: SubscriptionVerificationResult(
            entitlement: "member_monthly", status: "active", tier: .plus))
        continuation = nil
    }
}
