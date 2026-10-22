import Foundation

protocol SubscriptionStore: Sendable {
    func products() async throws -> [StoreSubscriptionProduct]
    func purchase(productID: String, appAccountToken: UUID) async throws -> StorePurchaseResult
    func currentEntitlements() async -> [StoreSignedTransaction]
    func synchronize() async throws
    func finish(transactionID: UInt64) async
}

protocol AppleSubscriptionVerifying: Sendable {
    func verify(signedTransaction: String) async throws -> SubscriptionVerificationResult
}
