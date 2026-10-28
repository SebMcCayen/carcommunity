import Foundation

protocol AccountAccessRepository: AnyObject, Sendable {
    func refreshProductAccess(uid: String)
    func updates(uid: String) -> AsyncStream<AccountAccessSnapshot>
}

extension AccountAccessRepository {
    func refreshProductAccess(uid: String) {}
}
