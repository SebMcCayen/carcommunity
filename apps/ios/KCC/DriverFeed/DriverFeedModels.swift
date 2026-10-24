import Foundation

let driverFeedPageSize = 20

struct DriverCursor: Equatable, Sendable {
    let seconds: Int64
    let nanoseconds: Int32
    let id: String
}

struct DriverPage<Item: Sendable>: Sendable {
    let items: [Item]
    let next: DriverCursor?
}

struct DriverPost: Identifiable, Equatable, Sendable {
    let id: String
    let authorUid: String
    let author: String
    let handle: String
    let text: String
    let createdAt: Date?
    let mediaURLs: [URL]
    let likes: Int
    let replies: Int

    static func decode(id: String, fields: [String: Any]) -> DriverPost? {
        guard validDriverId(id), let uid = fields["authorUid"] as? String, validDriverId(uid) else { return nil }
        let millis = fields["createdAtMillis"] as? NSNumber
        let media = (fields["mediaUrls"] as? [String] ?? []).compactMap(safeDriverMedia).prefix(4)
        return DriverPost(
            id: id, authorUid: uid, author: fields["authorName"] as? String ?? "",
            handle: fields["authorHandle"] as? String ?? "", text: fields["text"] as? String ?? "",
            createdAt: millis.map { Date(timeIntervalSince1970: $0.doubleValue / 1000) },
            mediaURLs: Array(media), likes: max(0, (fields["likeCount"] as? NSNumber)?.intValue ?? 0),
            replies: max(0, (fields["replyCount"] as? NSNumber)?.intValue ?? 0)
        )
    }
}

struct DriverComment: Identifiable, Equatable, Sendable {
    let id: String
    let authorUid: String
    let author: String
    let text: String
    let createdAt: Date?
}

enum DriverFeedFailure: Error, Equatable, Sendable {
    case error, unavailable, onboarding, canonicalOnboarding, restricted
}

struct DriverDetailPage: Sendable {
    let post: DriverPost
    let comments: DriverPage<DriverComment>
}

func validDriverId(_ value: String) -> Bool {
    !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && value.count <= 128
        && !value.contains("/") && value != "." && value != ".."
}

func safeDriverMedia(_ value: String) -> URL? {
    guard let url = URL(string: value), url.scheme == "https", let host = url.host,
        !host.isEmpty, url.user == nil, url.password == nil else { return nil }
    return url
}

struct DriverFeedStrings {
    let lookup: (String) -> String
    subscript(_ suffix: String) -> String { lookup("driver.feed.\(suffix)") }
}

protocol DriverFeedRepository: Sendable {
    func feed(uid: String, after: DriverCursor?) async throws -> DriverPage<DriverPost>
    func detail(uid: String, id: String, after: DriverCursor?) async throws -> DriverDetailPage
    func invalidations(uid: String, threadId: String?) -> AsyncStream<Void>
}
