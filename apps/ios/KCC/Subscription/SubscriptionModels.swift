import CryptoKit
import Foundation

let plusMonthlyProductID = "plus_monthly"
let supporterMonthlyProductID = "supporter_monthly"
let subscriptionProductIDs = [plusMonthlyProductID, supporterMonthlyProductID]

enum SubscriptionTier: String, Equatable, Sendable {
    case community
    case plus
    case supporter
}

struct StoreSubscriptionProduct: Equatable, Sendable, Identifiable {
    let id: String
    let displayPrice: String
}

struct StoreSignedTransaction: Equatable, Sendable {
    let id: UInt64
    let productID: String
    let signedTransaction: String
    var appAccountToken: UUID? = nil
}

enum StorePurchaseResult: Equatable, Sendable {
    case purchased(StoreSignedTransaction)
    case pending
    case cancelled
}

enum SubscriptionFlowStatus: Equatable, Sendable {
    case idle
    case loading
    case ready
    case purchasing
    case verifying
    case success(SubscriptionTier)
    case pending
    case failed(SubscriptionFailureReason)

    var isBusy: Bool {
        switch self {
        case .loading, .purchasing, .verifying: true
        default: false
        }
    }
}

enum SubscriptionFailureReason: Equatable, Sendable {
    case unavailable
    case productUnavailable
    case purchase
    case verification
    case inactivePurchase
}

struct SubscriptionVerificationResult: Equatable, Sendable {
    let entitlement: String
    let status: String
    let tier: SubscriptionTier

    var grantsAccess: Bool {
        entitlement == "member_monthly" && ["active", "grace_period", "cancelled"].contains(status)
    }
}

enum SubscriptionAccountToken {
    /// Matches `appleAppAccountTokenForUid` in the callable. StoreKit receives
    /// a one-way UUID binding, never the Firebase UID itself.
    static func forUID(_ uid: String) -> UUID? {
        var bytes = Array(SHA256Digest.hash(Data(uid.utf8)).prefix(16))
        guard bytes.count == 16 else { return nil }
        bytes[6] = (bytes[6] & 0x0f) | 0x50
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        let value = bytes.map { String(format: "%02x", $0) }.joined()
        return UUID(uuidString:
            "\(value.prefix(8))-\(value.dropFirst(8).prefix(4))-\(value.dropFirst(12).prefix(4))-" +
            "\(value.dropFirst(16).prefix(4))-\(value.dropFirst(20))"
        )
    }
}

private enum SHA256Digest {
    static func hash(_ data: Data) -> Data {
        // CryptoKit is part of iOS and keeps the account binding local.
        Data(CryptoKit.SHA256.hash(data: data))
    }
}

