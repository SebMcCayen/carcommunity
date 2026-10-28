import Foundation
import StoreKit

actor StoreKitSubscriptionStore: SubscriptionStore {
    private var productsByID: [String: Product] = [:]
    private var transactionsByID: [UInt64: Transaction] = [:]

    func products() async throws -> [StoreSubscriptionProduct] {
        let products = try await Product.products(for: subscriptionProductIDs)
            .sorted { subscriptionProductIDs.firstIndex(of: $0.id) ?? .max <
                subscriptionProductIDs.firstIndex(of: $1.id) ?? .max }
        guard StoreCatalogPolicy.permits(products.map { product in
            StoreCatalogEntry(id: product.id, isAutoRenewable: product.type == .autoRenewable,
                monthlyPeriod: product.subscription?.subscriptionPeriod.unit == .month
                    ? product.subscription?.subscriptionPeriod.value : nil,
                groupID: product.subscription?.subscriptionGroupID)
        })
        else {
            productsByID = [:]
            throw StoreKitSubscriptionError.productUnavailable
        }
        productsByID = Dictionary(uniqueKeysWithValues: products.map { ($0.id, $0) })
        return products.map { StoreSubscriptionProduct(id: $0.id, displayPrice: $0.displayPrice) }
    }

    func purchase(productID: String, appAccountToken: UUID) async throws -> StorePurchaseResult {
        // Refresh the complete catalogue: stale cached prices or an accidental
        // App Store group/period change must not charge a different offer.
        _ = try await products()
        guard let product = productsByID[productID] else {
            throw StoreKitSubscriptionError.productUnavailable
        }

        switch try await product.purchase(options: [.appAccountToken(appAccountToken)]) {
        case .success(let verification):
            switch verification {
            case .verified(let transaction):
                transactionsByID[transaction.id] = transaction
                return .purchased(StoreSignedTransaction(
                    id: transaction.id,
                    productID: transaction.productID,
                    signedTransaction: verification.jwsRepresentation,
                    appAccountToken: transaction.appAccountToken,
                    expiresAt: transaction.expirationDate,
                    isRevoked: transaction.revocationDate != nil,
                    isUpgraded: transaction.isUpgraded
                ))
            case .unverified:
                throw StoreKitSubscriptionError.unverifiedTransaction
            }
        case .pending:
            return .pending
        case .userCancelled:
            return .cancelled
        @unknown default:
            throw StoreKitSubscriptionError.unknownResult
        }
    }

    func currentEntitlements() async -> [StoreSignedTransaction] {
        var values: [StoreSignedTransaction] = []
        for await verification in Transaction.currentEntitlements {
            guard case .verified(let transaction) = verification,
                subscriptionProductIDs.contains(transaction.productID)
            else { continue }
            transactionsByID[transaction.id] = transaction
            values.append(StoreSignedTransaction(
                id: transaction.id,
                productID: transaction.productID,
                signedTransaction: verification.jwsRepresentation,
                appAccountToken: transaction.appAccountToken,
                expiresAt: transaction.expirationDate,
                isRevoked: transaction.revocationDate != nil,
                isUpgraded: transaction.isUpgraded
            ))
        }
        return values
    }

    func transactionUpdates() -> AsyncStream<StoreSignedTransaction> {
        let (stream, continuation) = AsyncStream<StoreSignedTransaction>.makeStream()
        let task = Task {
            for await verification in Transaction.updates {
                guard !Task.isCancelled else { break }
                if let signed = self.capture(verification) { continuation.yield(signed) }
            }
            continuation.finish()
        }
        continuation.onTermination = { _ in task.cancel() }
        return stream
    }

    func unfinishedTransactions() async -> [StoreSignedTransaction] {
        var pending: [StoreSignedTransaction] = []
        for await verification in Transaction.unfinished {
            if let signed = capture(verification) { pending.append(signed) }
        }
        return pending
    }

    private func capture(_ verification: VerificationResult<Transaction>) -> StoreSignedTransaction? {
        guard case .verified(let transaction) = verification,
              subscriptionProductIDs.contains(transaction.productID) else { return nil }
        transactionsByID[transaction.id] = transaction
        return StoreSignedTransaction(id: transaction.id, productID: transaction.productID,
                                      signedTransaction: verification.jwsRepresentation,
                                      appAccountToken: transaction.appAccountToken,
                                      expiresAt: transaction.expirationDate,
                                      isRevoked: transaction.revocationDate != nil,
                                      isUpgraded: transaction.isUpgraded)
    }

    func synchronize() async throws {
        try await AppStore.sync()
    }

    func finish(transactionID: UInt64) async {
        guard let transaction = transactionsByID.removeValue(forKey: transactionID) else { return }
        await transaction.finish()
    }
}

private enum StoreKitSubscriptionError: Error {
    case productUnavailable
    case unverifiedTransaction
    case unknownResult
}
