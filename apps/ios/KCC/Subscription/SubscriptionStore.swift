import Foundation

protocol SubscriptionStore: Sendable {
    func products() async throws -> [StoreSubscriptionProduct]
    func purchase(productID: String, appAccountToken: UUID) async throws -> StorePurchaseResult
    func currentEntitlements() async -> [StoreSignedTransaction]
    func transactionUpdates() async -> AsyncStream<StoreSignedTransaction>
    func unfinishedTransactions() async -> [StoreSignedTransaction]
    func synchronize() async throws
    func finish(transactionID: UInt64) async
}

protocol AppleSubscriptionVerifying: Sendable {
    func verify(signedTransaction: String, expectedUID: String) async throws -> SubscriptionVerificationResult
}
