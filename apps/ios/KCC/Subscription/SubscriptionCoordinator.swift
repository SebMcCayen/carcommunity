import Foundation
import Observation

@MainActor
@Observable
final class SubscriptionCoordinator {
    private let uid: String?
    private let store: (any SubscriptionStore)?
    private let verifier: (any AppleSubscriptionVerifying)?

    private(set) var products: [StoreSubscriptionProduct] = []
    private(set) var status: SubscriptionFlowStatus
    private(set) var currentTier: SubscriptionTier?
    @ObservationIgnored private var started = false

    init(
        uid: String?,
        store: (any SubscriptionStore)?,
        verifier: (any AppleSubscriptionVerifying)?
    ) {
        self.uid = uid
        self.store = store
        self.verifier = verifier
        status = (uid == nil || store == nil || verifier == nil) ? .failed(.unavailable) : .idle
    }

    var canPurchase: Bool {
        uid != nil && store != nil && verifier != nil && !status.isBusy && !products.isEmpty
    }

    func start() async {
        guard !started else { return }
        started = true
        await reloadAndReconcile()
    }

    func reloadAndReconcile() async {
        guard let store, verifier != nil, uid != nil else {
            status = .failed(.unavailable)
            return
        }
        guard !status.isBusy else { return }
        status = .loading
        do {
            products = try await store.products()
            let entitlements = await store.currentEntitlements()
            guard let preferred = Self.preferred(entitlements) else {
                status = products.isEmpty ? .failed(.unavailable) : .ready
                currentTier = nil
                return
            }
            await verify(preferred)
        } catch is CancellationError {
            status = .idle
        } catch {
            status = .failed(.unavailable)
        }
    }

    func purchase(_ productID: String) async {
        guard !status.isBusy,
            subscriptionProductIDs.contains(productID),
            products.contains(where: { $0.id == productID }),
            let uid,
            let store,
            verifier != nil,
            let token = SubscriptionAccountToken.forUID(uid)
        else {
            status = .failed(.productUnavailable)
            return
        }
        status = .purchasing
        do {
            switch try await store.purchase(productID: productID, appAccountToken: token) {
            case .purchased(let transaction):
                await verify(transaction)
            case .pending:
                status = .pending
            case .cancelled:
                status = .ready
            }
        } catch is CancellationError {
            status = .ready
        } catch {
            status = .failed(.purchase)
        }
    }

    func restore() async {
        guard !status.isBusy, let store, verifier != nil, uid != nil else { return }
        status = .loading
        do {
            try await store.synchronize()
            let entitlements = await store.currentEntitlements()
            guard let preferred = Self.preferred(entitlements) else {
                currentTier = nil
                status = .failed(.inactivePurchase)
                return
            }
            await verify(preferred)
        } catch is CancellationError {
            status = .ready
        } catch {
            status = .failed(.purchase)
        }
    }

    private func verify(_ transaction: StoreSignedTransaction) async {
        guard let verifier, let store else {
            status = .failed(.unavailable)
            return
        }
        status = .verifying
        do {
            let result = try await verifier.verify(signedTransaction: transaction.signedTransaction)
            await store.finish(transactionID: transaction.id)
            currentTier = result.grantsAccess ? result.tier : nil
            status = result.grantsAccess ? .success(result.tier) : .failed(.inactivePurchase)
        } catch is CancellationError {
            status = .ready
        } catch {
            // Deliberately do not finish: StoreKit will redeliver the verified
            // transaction so a transient backend failure cannot lose access.
            status = .failed(.verification)
        }
    }

    private static func preferred(_ transactions: [StoreSignedTransaction]) -> StoreSignedTransaction? {
        transactions.first(where: { $0.productID == supporterMonthlyProductID }) ??
            transactions.first(where: { $0.productID == plusMonthlyProductID })
    }
}
