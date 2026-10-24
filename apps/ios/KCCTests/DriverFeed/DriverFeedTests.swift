import Foundation
import XCTest
@testable import KCC

@MainActor
final class DriverFeedTests: XCTestCase {
    private let cursor = DriverCursor(seconds: 123, nanoseconds: 987654321, id: "last")
    private func post(_ id: String) -> DriverPost {
        DriverPost(id: id, authorUid: "author", author: "Author", handle: "member", text: "Original text",
            createdAt: nil, mediaURLs: [], likes: 2, replies: 1)
    }
    private func settle() async { for _ in 0..<40 { await Task.yield() } }

    func testOriginalContentAndNoFixtureFallbacks() {
        let value = DriverPost.decode(id: "post", fields: ["authorUid": "author", "text": "Original\ntext",
            "likeCount": -3, "replyCount": 7, "createdAtMillis": 123])!
        XCTAssertEqual(value.text, "Original\ntext")
        XCTAssertEqual(value.author, "")
        XCTAssertEqual(value.likes, 0)
        XCTAssertEqual(value.replies, 7)
        XCTAssertEqual(value.createdAt?.timeIntervalSince1970 ?? -1, 0.123, accuracy: 0.001)
    }

    func testInvalidTargetOrAuthorIsRejected() {
        XCTAssertNil(DriverPost.decode(id: "../post", fields: ["authorUid": "author"]))
        XCTAssertNil(DriverPost.decode(id: "post", fields: [:]))
        XCTAssertNil(DriverPost.decode(id: "post", fields: ["authorUid": ""]))
    }

    func testMediaRequiresHTTPSWithoutCredentialsAndCapsAtFour() {
        let media = ["http://example.com/plain", "file:///private/image", "https://user:pass@example.com/image"]
            + (1...6).map { "https://example.com/\($0).jpg" }
        let value = DriverPost.decode(id: "post", fields: ["authorUid": "author", "mediaUrls": media])!
        XCTAssertEqual(value.mediaURLs.map(\.absoluteString), (1...4).map { "https://example.com/\($0).jpg" })
    }

    func testEmptyFilteredPageCanContinue() async {
        let repo = FakeDriverFeedRepository()
        repo.pages = [DriverPage(items: [], next: cursor), DriverPage(items: [post("visible")], next: nil)]
        let model = DriverFeedCoordinator(repository: repo, uid: "uid")
        model.refresh(); await settle()
        XCTAssertTrue(model.state.posts.isEmpty)
        XCTAssertEqual(model.state.next, cursor)
        model.more(); await settle()
        XCTAssertEqual(repo.cursors, [nil, cursor])
        XCTAssertEqual(model.state.posts.first?.id, "visible")
    }

    func testPaginationDeduplicatesAndSerializesTaps() async {
        let repo = FakeDriverFeedRepository()
        repo.pages = [DriverPage(items: [post("same")], next: cursor), DriverPage(items: [post("same")], next: nil)]
        let model = DriverFeedCoordinator(repository: repo, uid: "uid")
        model.refresh(); await settle()
        model.more(); model.more(); await settle()
        XCTAssertEqual(repo.cursors.count, 2)
        XCTAssertEqual(model.state.posts.count, 1)
    }

    func testOriginalDetailUsesIndependentCommentCursor() async {
        let repo = FakeDriverFeedRepository()
        repo.pages = [DriverPage(items: [post("thread")], next: cursor)]
        repo.original = post("thread")
        repo.commentPages = [DriverPage(items: [], next: cursor), DriverPage(items: [], next: nil)]
        let model = DriverFeedCoordinator(repository: repo, uid: "uid")
        model.refresh(); await settle()
        model.open("thread"); await settle()
        model.more(); await settle()
        XCTAssertEqual(model.state.post?.id, "thread")
        XCTAssertEqual(repo.commentCursors, [nil, cursor])
        XCTAssertEqual(repo.threadIDs, ["thread", "thread"])
        model.back(); await settle()
        XCTAssertNil(model.state.selectedId)
    }

    func testAccessErrorClearsContentAndRetryRecovers() async {
        let repo = FakeDriverFeedRepository()
        repo.pages = [DriverPage(items: [post("original")], next: cursor)]
        let model = DriverFeedCoordinator(repository: repo, uid: "uid")
        model.refresh(); await settle()
        repo.failure = .onboarding
        model.more(); await settle()
        XCTAssertTrue(model.state.posts.isEmpty)
        XCTAssertEqual(model.state.failure, .onboarding)
        repo.failure = nil
        repo.pages = [DriverPage(items: [post("recovered")], next: nil)]
        model.refresh(); await settle()
        XCTAssertEqual(model.state.posts.first?.id, "recovered")
    }

    func testStopRejectsLateResponseFromOldAccount() async {
        let repo = FakeDriverFeedRepository()
        repo.suspendNext = true
        let model = DriverFeedCoordinator(repository: repo, uid: "old")
        model.refresh(); await settle()
        model.stop()
        repo.pending?.resume(returning: DriverPage(items: [post("old-private")], next: nil))
        repo.pending = nil
        await settle()
        XCTAssertEqual(model.state, DriverFeedState())
    }

    func testRefreshRejectsSupersededResponse() async {
        let repo = FakeDriverFeedRepository()
        repo.suspendNext = true
        let model = DriverFeedCoordinator(repository: repo, uid: "uid")
        model.refresh(); await settle()
        repo.pages = [DriverPage(items: [post("fresh")], next: nil)]
        model.refresh(); await settle()
        repo.pending?.resume(returning: DriverPage(items: [post("stale")], next: nil))
        repo.pending = nil
        await settle()
        XCTAssertEqual(model.state.posts.first?.id, "fresh")
    }

    func testMissingConfigurationAndLocalizationNamespace() {
        let model = DriverFeedCoordinator(repository: nil, uid: "uid")
        model.refresh()
        XCTAssertEqual(model.state.failure, .restricted)
        XCTAssertEqual(DriverFeedStrings(lookup: { $0 })["source"], "driver.feed.source")
    }

    func testWireCursorRetainsNanosecondsAndLimit() throws {
        let value = DriverCursor(seconds: 123, nanoseconds: 987654321, id: "lastpost1")
        let payload = driverPagePayload(value)
        XCTAssertEqual(payload["limit"] as? Int, 20)
        XCTAssertEqual(try driverWireCursor(["nextCursor": payload["cursor"]!]), value)
        XCTAssertNil(try driverWireCursor(["nextCursor": NSNull()]))
        XCTAssertNil(driverPagePayload(nil)["cursor"])
    }

    func testMalformedCursorAndOversizedResponsesFailClosed() {
        XCTAssertThrowsError(try driverWireCursor([:]))
        XCTAssertThrowsError(try driverWireCursor(["nextCursor": "bad"]))
        XCTAssertThrowsError(try driverWireCursor(["nextCursor": ["seconds": 123.5, "nanoseconds": 0, "id": "lastpost1"]]))
        XCTAssertThrowsError(try driverWireCursor(["nextCursor": ["seconds": 123, "nanoseconds": 1000000000, "id": "lastpost1"]]))
        XCTAssertThrowsError(try driverWireList(["posts": Array(repeating: [String: String](), count: 21)], key: "posts"))
        XCTAssertThrowsError(try driverWireList(["posts": ["bad"]], key: "posts"))
    }

    func testBridgeReasonMappingAndMissingDeployment() {
        XCTAssertEqual(driverReadFailure(reason: "canonical-onboarding-required", code: "FAILED_PRECONDITION"), .canonicalOnboarding)
        XCTAssertEqual(driverReadFailure(reason: "driver-onboarding-required", code: "FAILED_PRECONDITION"), .onboarding)
        XCTAssertEqual(driverReadFailure(reason: "account-restricted", code: "PERMISSION_DENIED"), .restricted)
        XCTAssertEqual(driverReadFailure(reason: "thread-unavailable", code: "NOT_FOUND"), .unavailable)
        XCTAssertEqual(driverReadFailure(reason: nil, code: "RESOURCE_EXHAUSTED"), .error)
        XCTAssertEqual(driverReadFailure(reason: nil, code: "NOT_FOUND"), .error)
    }

    func testAccessInvalidationImmediatelyClearsContent() async {
        let repo = FakeDriverFeedRepository()
        repo.pages = [DriverPage(items: [post("original")], next: nil)]
        let model = DriverFeedCoordinator(repository: repo, uid: "uid")
        model.refresh(); await settle()
        model.invalidate()
        XCTAssertTrue(model.state.posts.isEmpty)
        XCTAssertTrue(model.state.loading)
        model.stop()
    }
}

@MainActor
private final class FakeDriverFeedRepository: DriverFeedRepository {
    var pages: [DriverPage<DriverPost>] = []
    var commentPages: [DriverPage<DriverComment>] = []
    var original: DriverPost?
    var cursors: [DriverCursor?] = []
    var commentCursors: [DriverCursor?] = []
    var threadIDs: [String] = []
    var failure: DriverFeedFailure?
    var suspendNext = false
    var pending: CheckedContinuation<DriverPage<DriverPost>, Never>?

    func feed(uid: String, after: DriverCursor?) async throws -> DriverPage<DriverPost> {
        cursors.append(after)
        if let failure { throw failure }
        if suspendNext {
            suspendNext = false
            return await withCheckedContinuation { pending = $0 }
        }
        return pages.isEmpty ? DriverPage(items: [], next: nil) : pages.removeFirst()
    }
    func detail(uid: String, id: String, after: DriverCursor?) async throws -> DriverDetailPage {
        guard let original, original.id == id else { throw DriverFeedFailure.unavailable }
        threadIDs.append(id)
        commentCursors.append(after)
        let page = commentPages.isEmpty ? DriverPage<DriverComment>(items: [], next: nil) : commentPages.removeFirst()
        return DriverDetailPage(post: original, comments: page)
    }
    nonisolated func invalidations(uid: String, threadId: String?) -> AsyncStream<Void> {
        AsyncStream { $0.finish() }
    }
}
