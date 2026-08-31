import Foundation

/// The member's own Crown-Point balance, for the shop's affordability hint —
/// the iOS port of the balance half of Android's `PointsRepository`
/// (`observeBalance`). A single read of `pointsLedger/{uid}.balance` by
/// document id — NOT owner-only: firestore.rules grants `get` on this
/// document to any authenticated user (only the append-only `/entries`
/// subcollection underneath it is owner-scoped).
///
/// Firebase-free protocol so the shop coordinator is testable with a fake. The
/// balance is a DISPLAY hint only: the server re-checks it on every buy and
/// remains the sole authority on whether a debit lands, so a nil balance (no
/// read yet, or no ledger source wired) simply renders as 0 CP and every perk
/// as not-yet-affordable rather than blocking the list.
protocol PerkBalanceRepository: AnyObject, Sendable {
    /// The member's live balance in Crown Points; emits nil when the balance
    /// is absent (no ledger doc, or a missing `balance` field) so the shop
    /// still renders as 0 CP. A transient listener error keeps the last-known
    /// balance instead of emitting nil. Each call returns a fresh stream
    /// backed by its own listener.
    func balance(uid: String) -> AsyncStream<Int?>
}
