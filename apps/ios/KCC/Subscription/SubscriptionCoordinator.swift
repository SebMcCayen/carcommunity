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
        guard let verifier, let store, let uid else {
            status = .failed(.unavailable)
            return
        }
        status = .verifying
        do {
            let result = try await verifier.verify(signedTransaction: transaction.signedTransaction, expectedUID: uid)
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


/// Root-owned transaction delivery, independent of the membership screen.
/// Apple verification and account binding are checked before any backend call;
/// the backend remains authoritative for grants and revocations.
@MainActor
final class SubscriptionTransactionObserver {
    private let store: any SubscriptionStore
    private let verifier: (any AppleSubscriptionVerifying)?
    private let retryDelay: Duration
    private var generation = 0
    private var failed: [UInt64: StoreSignedTransaction] = [:]
    private var inFlight: Set<String> = []

    init(store: any SubscriptionStore, verifier: (any AppleSubscriptionVerifying)?,
         retryDelay: Duration = .seconds(15)) {
        self.store = store
        self.verifier = verifier
        self.retryDelay = retryDelay
    }

    func run(uid: String?) async {
        generation += 1
        let expectedGeneration = generation
        failed.removeAll()
        guard let uid, verifier != nil else { return }
        // Subscribe before reconciliation so approvals cannot fall in a gap.
        let updates = await store.transactionUpdates()
        let retry = Task { [weak self, retryDelay] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: retryDelay) } catch { return }
                guard let self, self.generation == expectedGeneration else { return }
                for transaction in Array(self.failed.values) {
                    await self.process(transaction, uid: uid, generation: expectedGeneration)
                }
            }
        }
        defer { retry.cancel() }
        for transaction in await store.unfinishedTransactions() {
            await process(transaction, uid: uid, generation: expectedGeneration)
        }
        for await transaction in updates {
            guard !Task.isCancelled, generation == expectedGeneration else { return }
            await process(transaction, uid: uid, generation: expectedGeneration)
        }
    }

    private func process(_ transaction: StoreSignedTransaction, uid: String, generation expected: Int) async {
        guard !Task.isCancelled, generation == expected, let verifier,
              transaction.appAccountToken == SubscriptionAccountToken.forUID(uid),
              subscriptionProductIDs.contains(transaction.productID) else { return }
        let key = "\(expected):\(transaction.id)"
        guard inFlight.insert(key).inserted else { return }
        defer { inFlight.remove(key) }
        do {
            var authoritative = transaction
            if transaction.isInactive(at: Date()) {
                // Old unfinished periods and upgrade/revocation updates must
                // not revoke a newer active subscription on the same account.
                let active = await store.currentEntitlements().filter {
                    $0.id != transaction.id && $0.appAccountToken == SubscriptionAccountToken.forUID(uid)
                        && !$0.isInactive(at: Date()) && subscriptionProductIDs.contains($0.productID)
                }
                authoritative = active.first(where: { $0.productID == supporterMonthlyProductID })
                    ?? active.first ?? transaction
            }
            guard !Task.isCancelled, generation == expected else { return }
            _ = try await verifier.verify(signedTransaction: authoritative.signedTransaction, expectedUID: uid)
            guard !Task.isCancelled, generation == expected else { return }
            if authoritative.id != transaction.id {
                await store.finish(transactionID: authoritative.id)
            }
            await store.finish(transactionID: transaction.id)
            guard !Task.isCancelled, generation == expected else { return }
            failed.removeValue(forKey: transaction.id)
        } catch {
            guard !Task.isCancelled, generation == expected else { return }
            failed[transaction.id] = transaction
        }
    }
}
