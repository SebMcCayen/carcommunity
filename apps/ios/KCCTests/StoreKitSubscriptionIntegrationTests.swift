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
        guard session.disableDialogs, session.storefront == "SWE" else {
            throw NSError(domain: "StoreKitIntegrationEnvironment", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "StoreKit test service did not accept its configuration. " +
                    "Use iOS 26.2 or a runtime with Apple's FB22500243 fix (iOS 26.6)."
            ])
        }
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
        let restored = try await waitForPublishedTransactions(session, stage: "restored entitlements") {
            await restoredStore.currentEntitlements()
        }
        XCTAssertEqual(restored.map(\.id), [purchase.id])
        let unfinished = try await waitForPublishedTransactions(session, stage: "unfinished transactions") {
            await store.unfinishedTransactions()
        }
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

    /// StoreKit can return the purchase before publishing its transaction sequences.
    /// Return the first nonempty snapshot, including unexpected transactions, so
    /// the caller's exact-ID assertions cannot be hidden by subsequent polling.
    private func waitForPublishedTransactions(
        _ session: SKTestSession,
        stage: String,
        read: () async -> [StoreSignedTransaction]
    ) async throws -> [StoreSignedTransaction] {
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        var observedIDs: [UInt64] = []
        repeat {
            let transactions = await read()
            observedIDs = transactions.map(\.id)
            if !transactions.isEmpty { return transactions }
            try await Task.sleep(for: .milliseconds(50))
        } while ContinuousClock.now < deadline
        let localIDs = session.allTransactions().map(\.identifier)
        throw NSError(domain: "StoreKitIntegrationTestTimeout", code: 2, userInfo: [
            NSLocalizedDescriptionKey: "Timed out waiting for \(stage). " +
                "Observed IDs: \(observedIDs); local StoreKit test transaction IDs: \(localIDs)"
        ])
    }
}
