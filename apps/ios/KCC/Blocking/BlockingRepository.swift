import Foundation

/// Owner-scoped blocking access. Reads expose only the caller's own outgoing
/// block edges; all mutations go through the authoritative callables.
protocol BlockingRepository: AnyObject, Sendable {
    func observeBlocked(uid: String) -> AsyncStream<BlockedUsersSnapshot>
    func block(targetUserId: String) async throws
    func unblock(targetUserId: String) async throws
}
