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
}

private actor FakeSubscriptionStore: SubscriptionStore {
    private let availableProducts: [StoreSubscriptionProduct]
    private let purchaseResult: StorePurchaseResult
    private let entitlements: [StoreSignedTransaction]
    private(set) var finishedIDs: [UInt64] = []

    init(
        products: [StoreSubscriptionProduct] = [
            StoreSubscriptionProduct(id: plusMonthlyProductID, displayPrice: "SEK 39"),
            StoreSubscriptionProduct(id: supporterMonthlyProductID, displayPrice: "SEK 119"),
        ],
        purchaseResult: StorePurchaseResult = .cancelled,
        entitlements: [StoreSignedTransaction] = []
    ) {
        availableProducts = products
        self.purchaseResult = purchaseResult
        self.entitlements = entitlements
    }

    func products() async throws -> [StoreSubscriptionProduct] { availableProducts }
    func purchase(productID: String, appAccountToken: UUID) async throws -> StorePurchaseResult {
        purchaseResult
    }
    func currentEntitlements() async -> [StoreSignedTransaction] { entitlements }
    func synchronize() async throws {}
    func finish(transactionID: UInt64) async { finishedIDs.append(transactionID) }
}

private actor FakeAppleVerifier: AppleSubscriptionVerifying {
    private let result: Result<SubscriptionVerificationResult, Error>
    private(set) var calls = 0

    init(result: Result<SubscriptionVerificationResult, Error>) {
        self.result = result
    }

    func verify(signedTransaction: String) async throws -> SubscriptionVerificationResult {
        calls += 1
        return try result.get()
    }
}

private enum TestError: Error {
    case failed
}
