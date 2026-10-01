import Foundation

protocol MemberProfileRepository: AnyObject, Sendable {
    /// Reads only authenticated-public documents. It never reads badge progress,
    /// points entries, drives, subscription data, or another user's friend graph.
    func load(targetUid: String) async -> MemberProfileResult
    func imageDownloadURL(for path: String) async -> URL?
}
