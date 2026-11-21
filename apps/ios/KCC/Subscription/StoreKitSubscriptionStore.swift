import Foundation
import StoreKit

actor StoreKitSubscriptionStore: SubscriptionStore {
    private var productsByID: [String: Product] = [:]
    private var transactionsByID: [UInt64: Transaction] = [:]

    func products() async throws -> [StoreSubscriptionProduct] {
        let fetched: [Product]
        do {
            fetched = try await Product.products(for: subscriptionProductIDs)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw SubscriptionStoreFailure.catalogueUnavailable
        }
        let products = fetched
            .sorted { subscriptionProductIDs.firstIndex(of: $0.id) ?? .max <
                subscriptionProductIDs.firstIndex(of: $1.id) ?? .max }
        guard StoreCatalogPolicy.permits(products.map { product in
            StoreCatalogEntry(id: product.id, isAutoRenewable: product.type == .autoRenewable,
                monthlyPeriod: product.subscription?.subscriptionPeriod.unit == .month
                    ? product.subscription?.subscriptionPeriod.value : nil,
                groupID: product.subscription?.subscriptionGroupID)
        })
        else {
            throw SubscriptionStoreFailure.catalogueUnavailable
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
                    isUpgraded: transaction.isUpgraded,
                originalID: transaction.originalID, subscriptionGroupID: transaction.subscriptionGroupID
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
                isUpgraded: transaction.isUpgraded,
                originalID: transaction.originalID, subscriptionGroupID: transaction.subscriptionGroupID
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

    func scheduledChange(for transaction: StoreSignedTransaction, appAccountToken: UUID) async throws -> ScheduledSubscriptionChange? {
        guard transaction.appAccountToken == appAccountToken,
              let originalID = transaction.originalID,
              let groupID = transaction.subscriptionGroupID else { return nil }
        let statuses = try await Product.SubscriptionInfo.status(for: groupID)
        for status in statuses {
            guard case .verified(let current) = status.transaction,
                  case .verified(let renewal) = status.renewalInfo else {
                throw StoreKitSubscriptionError.unverifiedTransaction
            }
            guard current.id == transaction.id, current.originalID == originalID,
                  current.appAccountToken == appAccountToken,
                  renewal.originalTransactionID == originalID,
                  renewal.currentProductID == current.productID,
                  current.revocationDate == nil, !current.isUpgraded,
                  current.expirationDate.map({ $0 > Date() }) == true else { continue }
            return ScheduledSubscriptionChange.downgrade(
                currentProductID: current.productID, nextProductID: renewal.autoRenewPreference,
                willAutoRenew: renewal.willAutoRenew,
                effectiveAt: renewal.renewalDate ?? current.expirationDate, now: Date())
        }
        return nil
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
                                      isUpgraded: transaction.isUpgraded,
                                      originalID: transaction.originalID, subscriptionGroupID: transaction.subscriptionGroupID)
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
