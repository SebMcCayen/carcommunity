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

enum SubscriptionStoreFailure: Error {
    case catalogueUnavailable
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
    var expiresAt: Date? = nil
    var isRevoked = false
    var isUpgraded = false
    var originalID: UInt64? = nil
    var subscriptionGroupID: String? = nil

    func isInactive(at now: Date) -> Bool {
        isRevoked || isUpgraded || expiresAt.map { $0 <= now } == true
    }
}

/// Display-only renewal preference. Never grants the future tier early.
struct ScheduledSubscriptionChange: Equatable, Sendable {
    let productID: String
    let effectiveAt: Date

    static func downgrade(currentProductID: String, nextProductID: String?,
                          willAutoRenew: Bool, effectiveAt: Date?, now: Date) -> Self? {
        guard currentProductID == supporterMonthlyProductID,
              nextProductID == plusMonthlyProductID, willAutoRenew,
              let effectiveAt, effectiveAt > now else { return nil }
        return Self(productID: plusMonthlyProductID, effectiveAt: effectiveAt)
    }
}

enum StorePurchaseResult: Equatable, Sendable {
    case purchased(StoreSignedTransaction)
    case pending
    case cancelled
}

enum SubscriptionOperation: Equatable, Sendable {
    case purchase
    case restore
    case verification
    case planChange
}

enum SubscriptionFlowStatus: Equatable, Sendable {
    case idle
    case loading
    case ready
    case purchasing
    case restoring
    case verifying
    case success(SubscriptionTier, operation: SubscriptionOperation)
    case pending
    case failed(SubscriptionFailureReason)

    var isBusy: Bool {
        switch self {
        case .loading, .purchasing, .restoring, .verifying: true
        default: false
        }
    }

    var messageKey: String? {
        switch self {
        case .idle: nil
        case .loading: "subscription.statusConnecting"
        case .ready: "subscription.statusReady"
        case .purchasing: "subscription.statusPurchasing"
        case .restoring: "subscription.statusRestoring"
        case .verifying: "subscription.statusVerifying"
        case .success(_, let operation):
            switch operation {
            case .purchase: "subscription.statusSuccess"
            case .restore: "subscription.statusRestored"
            case .verification: "subscription.statusVerified"
            case .planChange: "subscription.statusChangeScheduled"
            }
        case .pending: "subscription.statusPending"
        case .failed(let reason):
            switch reason {
            case .unavailable: "subscription.statusUnavailable"
            case .productUnavailable: "subscription.errorProductUnavailable"
            case .purchase: "subscription.errorPurchase"
            case .verification: "subscription.errorVerification"
            case .restore: "subscription.errorRestore"
            case .restoreVerification: "subscription.errorRestoreVerification"
            case .membershipVerification: "subscription.errorMembershipVerification"
            case .noPurchasesToRestore: "subscription.errorNoPurchasesToRestore"
            case .inactivePurchase: "subscription.errorInactivePurchase"
            }
        }
    }
}

enum SubscriptionFailureReason: Equatable, Sendable {
    case unavailable
    case productUnavailable
    case purchase
    case verification
    case restore
    case restoreVerification
    case membershipVerification
    case noPurchasesToRestore
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


/// Snapshot of App Store configuration, kept separate from transactions.
struct StoreCatalogEntry: Sendable {
    let id: String
    let isAutoRenewable: Bool
    let monthlyPeriod: Int?
    let groupID: String?
}

enum StoreCatalogPolicy {
    static func permits(_ entries: [StoreCatalogEntry]) -> Bool {
        // Both known plans are required to verify their shared group, even if
        // one SKU temporarily disappears from the store response. Otherwise an
        // existing subscriber could buy the remaining SKU in a separate group.
        guard entries.count == subscriptionProductIDs.count,
              Set(entries.map(\.id)) == Set(subscriptionProductIDs) else { return false }
        return entries.allSatisfy {
            subscriptionProductIDs.contains($0.id) && $0.isAutoRenewable
                && $0.monthlyPeriod == 1 && $0.groupID?.isEmpty == false
        } && Set(entries.compactMap(\.groupID)).count <= 1
    }
}
