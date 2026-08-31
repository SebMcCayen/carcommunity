import Foundation
import Observation

/// UI-facing state of the whole shop tab (catalog + inventory + balance) — the
/// iOS port of Android's `PerkShopUiState`, with the empty and unavailable
/// cases lifted into the state so the SwiftUI view stays a dumb switch.
enum PerkShopUiState: Equatable, Sendable {
    case loading
    /// The catalog loaded but carries no buyable perks.
    case empty
    /// The current balance + the resolved shop rows.
    case loaded(balanceKp: Int, items: [PerkShopItem])
    /// The `crownHuntPerks` flag is off, no repository in this build, or no
    /// session — the shop tab must never render. Mirrors Android gating the
    /// whole shop dark behind the contract-default-OFF flag.
    case unavailable
    case failed(code: String?)
}

/// UI-facing status of the buy flow — Android's `PerkBuyStatus`. Carries the
/// perkId so a per-row spinner/message can target one card.
enum PerkBuyStatus: Equatable, Sendable {
    case idle
    /// A buy for `perkId` is in flight — the row's button shows a spinner.
    case buying(perkId: String)
    /// Bought `perkId`; the post-purchase totals for the banner.
    case bought(perkId: String, newBalance: Int, inventoryCount: Int, alreadyPurchased: Bool)
    case failed(perkId: String, reason: PerkBuyFailureReason)
}

/// Orchestrates the shop: combines the catalog + inventory + balance reads into
/// one render state, and runs a single guarded purchase. Pure Swift (no
/// Firebase/SwiftUI) so it is unit-testable with fakes — the iOS counterpart of
/// Android's `combineShop` + `PerkShopCoordinator`.
///
/// Gating mirrors Android exactly: the shop is wired only when the
/// `crownHuntPerks` flag is on, the member gate passes and a repository + uid
/// exist; otherwise nothing subscribes and the state is
/// ``PerkShopUiState/unavailable`` (the shop ships dark).
@MainActor
@Observable
final class PerkShopCoordinator {
    private let repository: PerkShopRepository?
    private let balanceRepository: PerkBalanceRepository?
    private let uid: String?
    private let perksEnabled: Bool
    private let passesMemberGate: Bool
    private let keyFactory: @Sendable () -> String

    @ObservationIgnored
    nonisolated(unsafe) private var catalogTask: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var inventoryTask: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var balanceTask: Task<Void, Never>?
    @ObservationIgnored
    private var started = false

    // Latest emissions from each stream; the render state is recomposed on
    // every change (the pure ``PerkShop/items`` fold, like Android's combine).
    @ObservationIgnored private var latestCatalog: PerkCatalogSnapshot?
    @ObservationIgnored private var latestInventory: [String: Int] = [:]
    @ObservationIgnored private var latestBalance: Int?

    private(set) var state: PerkShopUiState = .loading
    private(set) var buyStatus: PerkBuyStatus = .idle

    init(
        repository: PerkShopRepository?,
        balanceRepository: PerkBalanceRepository?,
        uid: String?,
        perksEnabled: Bool,
        passesMemberGate: Bool,
        keyFactory: @escaping @Sendable () -> String = { UUID().uuidString }
    ) {
        self.repository = repository
        self.balanceRepository = balanceRepository
        self.uid = uid
        self.perksEnabled = perksEnabled
        self.passesMemberGate = passesMemberGate
        self.keyFactory = keyFactory
    }

    deinit {
        catalogTask?.cancel()
        inventoryTask?.cancel()
        balanceTask?.cancel()
    }

    /// Whether the shop is wired in this build — the gate Android applies before
    /// subscribing any shop flow. The SwiftUI tab bar reads this to decide
    /// whether to OFFER the Shop tab at all.
    var isShopEnabled: Bool {
        perksEnabled && repository != nil && uid != nil && passesMemberGate
    }

    /// Begins the reads on first appearance. Idempotent once started: a
    /// second call while already started is a no-op. `started` is set only
    /// once the gate passes, so a call while the shop is off/ungated does
    /// NOT latch `started` — every subsequent call re-checks
    /// ``isShopEnabled`` instead of permanently no-op'ing. This does NOT mean
    /// eligibility can change for a live instance: `uid`, `perksEnabled`, and
    /// `passesMemberGate` are all `let`s fixed at construction, so
    /// ``isShopEnabled`` never flips for the same coordinator. The re-check
    /// only matters across re-construction (e.g. SwiftUI building a fresh
    /// coordinator once a session/flag becomes known) — never a change of
    /// mind on this instance.
    func start() {
        guard !started else { return }
        guard isShopEnabled else {
            state = .unavailable
            return
        }
        started = true
        subscribe()
    }

    /// Tears down any current subscriptions and re-subscribes from scratch —
    /// the shop's "try again" affordance, mirroring the other coordinators'
    /// `reload()`. Unlike ``start()`` this always re-checks the gate, so a
    /// retry tap after a failed read is never a no-op.
    func reload() {
        guard isShopEnabled else {
            state = .unavailable
            return
        }
        started = true
        subscribe()
    }

    private func subscribe() {
        catalogTask?.cancel()
        inventoryTask?.cancel()
        balanceTask?.cancel()
        // Clear every cached emission along with the tasks that produced
        // them: a bare cancel() left them in place, so a reload() could
        // recompose against the PREVIOUS subscription's catalog/inventory/
        // balance the instant a new stream emitted before the others — a
        // "re-subscribes from scratch" that was not actually from scratch.
        latestCatalog = nil
        latestInventory = [:]
        latestBalance = nil
        guard let repository, let uid else {
            state = .unavailable
            return
        }
        state = .loading

        let catalogStream = repository.catalog()
        catalogTask = Task { [weak self] in
            for await snapshot in catalogStream {
                guard !Task.isCancelled, let self else { return }
                self.latestCatalog = snapshot
                self.recompose()
            }
        }

        let inventoryStream = repository.inventory(uid: uid)
        inventoryTask = Task { [weak self] in
            for await inventory in inventoryStream {
                guard !Task.isCancelled, let self else { return }
                self.latestInventory = inventory
                self.recompose()
            }
        }

        if let balanceRepository {
            let balanceStream = balanceRepository.balance(uid: uid)
            balanceTask = Task { [weak self] in
                for await balance in balanceStream {
                    guard !Task.isCancelled, let self else { return }
                    self.latestBalance = balance
                    self.recompose()
                }
            }
        }
    }

    /// Buys one unit of `perkId`. `affordable` is the row's display hint: when
    /// false the buy short-circuits to ``PerkBuyFailureReason/insufficientFunds``
    /// without calling the backend (the server remains the authority and its own
    /// overdraft rejection maps to the same reason). A second buy while one is
    /// in flight is dropped before any suspension point — the same double-buy
    /// guard as Android's `PerkShopCoordinator`.
    func buy(perkId: String, affordable: Bool) async {
        guard let repository, isShopEnabled else { return }
        if case .buying = buyStatus { return }

        guard affordable else {
            buyStatus = .failed(perkId: perkId, reason: .insufficientFunds)
            return
        }

        buyStatus = .buying(perkId: perkId)
        do {
            let result = try await repository.buyPerk(perkId: perkId, idempotencyKey: keyFactory())
            buyStatus = .bought(
                perkId: result.perkId,
                newBalance: result.newBalance,
                inventoryCount: result.inventoryCount,
                alreadyPurchased: result.alreadyPurchased
            )
        } catch let error as PerkPurchaseError {
            buyStatus = .failed(perkId: perkId, reason: error.reason)
        } catch is CancellationError {
            buyStatus = .idle
        } catch {
            buyStatus = .failed(perkId: perkId, reason: .unknown)
        }
    }

    /// Clears a terminal buy status (bought/failed) so the shop is fresh again.
    func resetBuyStatus() {
        if case .buying = buyStatus { return }
        buyStatus = .idle
    }

    private func recompose() {
        guard let latestCatalog else {
            state = .loading
            return
        }
        switch latestCatalog {
        case .failed(let code):
            state = .failed(code: code)
        case .loaded(let entries):
            if entries.isEmpty {
                state = .empty
                return
            }
            let resolved = PerkShop.items(
                catalog: entries,
                inventory: latestInventory,
                balanceKp: latestBalance
            )
            state = .loaded(balanceKp: resolved.balanceKp, items: resolved.items)
        }
    }
}
