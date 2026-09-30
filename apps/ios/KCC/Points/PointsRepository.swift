import Foundation

/// Read-only access to the signed-in member's points statement. Clients never
/// write balances or rows; the backend owns the append-only ledger.
protocol PointsRepository: AnyObject, Sendable {
    func observeBalance(uid: String) -> AsyncStream<Int64?>
    func observeEntries(uid: String) -> AsyncStream<PointsEntriesSnapshot>
}
