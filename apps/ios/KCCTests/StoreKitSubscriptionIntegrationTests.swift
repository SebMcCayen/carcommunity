import XCTest
import StoreKit
import StoreKitTest
@testable import KCC

/// Real StoreKit transactions, local to Xcode. No Firebase or paid store setup.
@MainActor
final class StoreKitSubscriptionIntegrationTests: XCTestCase {
    private func session() throws -> SKTestSession {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Subscriptions", withExtension: "storekit"))
        let session = try SKTestSession(contentsOf: url)
        session.resetToDefaultState()
        session.clearTransactions()
        session.disableDialogs = true
        session.storefront = "SWE"
        return session
    }

    func testPurchaseRestoreRenewalAndRefund() async throws {
        let session = try session()
        defer { session.clearTransactions() }
        let store = StoreKitSubscriptionStore()
        let products = try await store.products()
        XCTAssertEqual(products.map(\.id), subscriptionProductIDs)
        XCTAssertTrue(products.allSatisfy { !$0.displayPrice.isEmpty })
        let token = try XCTUnwrap(SubscriptionAccountToken.forUID("storekit-local-test"))
        guard case .purchased(let purchase) = try await store.purchase(productID: plusMonthlyProductID, appAccountToken: token) else {
            return XCTFail("Expected a verified local StoreKit purchase")
        }
        XCTAssertEqual(purchase.appAccountToken, token)
        XCTAssertFalse(purchase.signedTransaction.isEmpty)
        XCTAssertFalse(purchase.isInactive(at: Date()))

        // A new store instance sees the purchase; finishing is deliberately a
        // separate operation, after the production server would have verified it.
        let restoredStore = StoreKitSubscriptionStore()
        try await restoredStore.synchronize()
        let restored = await restoredStore.currentEntitlements()
        XCTAssertEqual(restored.map(\.id), [purchase.id])
        let unfinished = await store.unfinishedTransactions()
        XCTAssertTrue(unfinished.contains { $0.id == purchase.id })
        await store.finish(transactionID: purchase.id)

        try session.forceRenewalOfSubscription(productIdentifier: plusMonthlyProductID)
        let renewal = try await waitForEntitlement(store) { $0.id != purchase.id }
        XCTAssertEqual(renewal.appAccountToken, token)
        try session.refundTransaction(identifier: UInt(renewal.id))
        for _ in 0..<100 {
            if await store.currentEntitlements().isEmpty { return }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTFail("Refunded membership remained in current entitlements")
    }

    func testPendingPurchaseAndApproval() async throws {
        let session = try session()
        defer { session.clearTransactions() }
        session.askToBuyEnabled = true
        let store = StoreKitSubscriptionStore()
        let token = try XCTUnwrap(SubscriptionAccountToken.forUID("storekit-local-test"))
        let result = try await store.purchase(productID: supporterMonthlyProductID, appAccountToken: token)
        guard case .pending = result else { return XCTFail("Ask to Buy must remain pending") }
        let entitlements = await store.currentEntitlements()
        XCTAssertTrue(entitlements.isEmpty)
        let pending = try XCTUnwrap(session.allTransactions().last)
        try session.approveAskToBuyTransaction(identifier: pending.identifier)
        let approved = try await waitForEntitlement(store) { $0.productID == supporterMonthlyProductID }
        XCTAssertEqual(approved.appAccountToken, token)
    }

    private func waitForEntitlement(
        _ store: StoreKitSubscriptionStore,
        matching predicate: (StoreSignedTransaction) -> Bool
    ) async throws -> StoreSignedTransaction {
        for _ in 0..<100 {
            if let transaction = await store.currentEntitlements().first(where: predicate) { return transaction }
            try await Task.sleep(for: .milliseconds(50))
        }
        throw NSError(domain: "StoreKitIntegrationTestTimeout", code: 1)
    }
}
