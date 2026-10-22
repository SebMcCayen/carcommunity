import Foundation
import StoreKit

actor StoreKitSubscriptionStore: SubscriptionStore {
    private var productsByID: [String: Product] = [:]
    private var transactionsByID: [UInt64: Transaction] = [:]

    func products() async throws -> [StoreSubscriptionProduct] {
        let products = try await Product.products(for: subscriptionProductIDs)
            .sorted { subscriptionProductIDs.firstIndex(of: $0.id) ?? .max <
                subscriptionProductIDs.firstIndex(of: $1.id) ?? .max }
        productsByID = Dictionary(uniqueKeysWithValues: products.map { ($0.id, $0) })
        return products.map { StoreSubscriptionProduct(id: $0.id, displayPrice: $0.displayPrice) }
    }

    func purchase(productID: String, appAccountToken: UUID) async throws -> StorePurchaseResult {
        let product: Product
        if let cached = productsByID[productID] {
            product = cached
        } else {
            guard let loaded = try await Product.products(for: [productID]).first else {
                throw StoreKitSubscriptionError.productUnavailable
            }
            product = loaded
            productsByID[productID] = loaded
        }

        switch try await product.purchase(options: [.appAccountToken(appAccountToken)]) {
        case .success(let verification):
            switch verification {
            case .verified(let transaction):
                transactionsByID[transaction.id] = transaction
                return .purchased(StoreSignedTransaction(
                    id: transaction.id,
                    productID: transaction.productID,
                    signedTransaction: verification.jwsRepresentation
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
                signedTransaction: verification.jwsRepresentation
            ))
        }
        return values
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
