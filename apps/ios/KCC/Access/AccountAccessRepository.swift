import Foundation

protocol AccountAccessRepository: AnyObject, Sendable {
    func updates(uid: String) -> AsyncStream<AccountAccessSnapshot>
}
