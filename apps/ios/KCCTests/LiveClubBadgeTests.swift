import SwiftUI
import UIKit
import XCTest
@testable import KCC

@MainActor
final class LiveClubBadgeTests: XCTestCase {
    private let logo = "https://firebasestorage.googleapis.com/v0/b/test.appspot.com/o/clubLogos%2Ftest-club%2Fowner%2Flogo.jpg?alt=media&token=test"

    func testCanonicalAndroidProjectionKeepsLogoAndInitials() throws {
        let badge = try XCTUnwrap(LiveClubBadge.decode(fields(logo)))
        XCTAssertEqual(badge.clubID, "test-club")
        XCTAssertEqual(badge.clubName, "Test Club")
        XCTAssertEqual(badge.logoURL?.absoluteString, logo)
        XCTAssertEqual(badge.initials, "TC")
        XCTAssertEqual(LiveClubBadge.decode(fields(nil))?.initials, "TC")
        XCTAssertEqual(LiveClubBadge.decode([
            "clubId": "test-club", "clubName": "🚗 Motor Klubb"
        ])?.initials, "🚗M")
    }

    func testMissingMalformedOrPrivilegedAffiliationIsAbsent() {
        let values: [Any?] = [nil, NSNull(), [:], ["clubId": "../private", "clubName": "Test Club"],
            ["clubId": "test-club", "clubName": " Test Club"],
            ["clubId": "test-club", "clubName": "Test\nClub"],
            ["clubId": "test-club", "clubName": "Test Club", "role": "owner"],
            ["clubId": "test-club", "clubName": "Test Club", "logoUrl": 123]]
        for value in values {
            XCTAssertNil(LiveClubBadge.decode(value))
        }
    }

    func testUnsafeOrOtherClubLogoKeepsInitialsWithoutImage() throws {
        for url in ["https://example.com/logo.jpg", logo.replacingOccurrences(of: "https:", with: "http:"),
            logo.replacingOccurrences(of: "test-club", with: "other-club"),
            logo.replacingOccurrences(of: "clubLogos", with: "profileImages"),
            logo.replacingOccurrences(of: "logo.jpg", with: ".."),
            logo.replacingOccurrences(of: "%2F", with: "%252F"),
            logo.replacingOccurrences(of: "https://", with: "https://user:password@"),
            logo.replacingOccurrences(of: ".com/v0", with: ".com:443/v0"),
            logo + "#fragment", String(repeating: "x", count: 2049)] {
            let badge = try XCTUnwrap(LiveClubBadge.decode(fields(url)))
            XCTAssertNil(badge.logoURL, url)
            XCTAssertEqual(badge.initials, "TC")
        }
    }

    func testDrawnRosterUpdatesBadgeAndClearsOnMissingProfile() async throws {
        let repository = FakeRepository()
        let coordinator = LiveClubBadgeCoordinator { _ in repository }
        coordinator.setRoster(viewerID: "viewer", drawnUIDs: ["nearby", "convoy"])
        await wait { repository.opened.count == 2 }
        let badge = try XCTUnwrap(LiveClubBadge.decode(fields(logo)))
        repository.emit(badge, uid: "nearby")
        await wait { coordinator.badges["nearby"] == badge }
        XCTAssertEqual(coordinator.badge(for: "nearby", viewerID: "viewer"), badge)
        XCTAssertNil(coordinator.badge(for: "nearby", viewerID: "other-viewer"))
        XCTAssertNil(coordinator.badge(for: "nearby", viewerID: nil))
        let renamed = try XCTUnwrap(LiveClubBadge.decode(["clubId": "new-club", "clubName": "New Club"]))
        repository.emit(renamed, uid: "nearby")
        await wait { coordinator.badges["nearby"] == renamed }
        repository.emit(nil, uid: "nearby")
        await wait { coordinator.badges["nearby"] == nil }
        coordinator.stop()
    }

    func testPanningOffMapCancelsOnlyRemovedProfileAndIgnoresLateSnapshot() async throws {
        let repository = FakeRepository()
        let coordinator = LiveClubBadgeCoordinator { _ in repository }
        coordinator.setRoster(viewerID: "viewer", drawnUIDs: ["nearby", "convoy"])
        await wait { repository.opened.count == 2 }
        let oldStream = try XCTUnwrap(repository.streams["nearby"])
        let badge = try XCTUnwrap(LiveClubBadge.decode(fields(logo)))
        repository.emit(badge, uid: "nearby")
        await wait { coordinator.badges["nearby"] != nil }
        coordinator.setRoster(viewerID: "viewer", drawnUIDs: ["convoy"])
        XCTAssertNil(coordinator.badges["nearby"])
        oldStream.yield(badge)
        await wait { repository.cancelled.contains("nearby") }
        XCTAssertNil(coordinator.badges["nearby"])
        XCTAssertFalse(repository.cancelled.contains("convoy"))
        XCTAssertEqual(repository.opened.count, 2)
        coordinator.stop()
    }

    func testViewerSwitchAndBackgroundClearBadgesAndCancelListeners() async throws {
        let first = FakeRepository(), second = FakeRepository()
        let coordinator = LiveClubBadgeCoordinator { $0 == "first" ? first : second }
        let badge = try XCTUnwrap(LiveClubBadge.decode(fields(logo)))
        coordinator.setRoster(viewerID: "first", drawnUIDs: ["driver"])
        await wait { first.opened == ["driver"] }
        first.emit(badge, uid: "driver")
        await wait { !coordinator.badges.isEmpty }
        coordinator.setRoster(viewerID: "second", drawnUIDs: ["driver"])
        XCTAssertTrue(coordinator.badges.isEmpty)
        await wait { first.cancelled == ["driver"] && second.opened == ["driver"] }
        second.emit(badge, uid: "driver")
        await wait { !coordinator.badges.isEmpty }
        coordinator.setRoster(viewerID: nil, drawnUIDs: ["driver"])
        XCTAssertTrue(coordinator.badges.isEmpty)
        await wait { second.cancelled == ["driver"] }
        XCTAssertNil(coordinator.viewerID)
    }

    func testProfileBudgetDeduplicationAndInvalidUIDs() async {
        let repository = FakeRepository()
        let coordinator = LiveClubBadgeCoordinator { _ in repository }
        let uids = Set((0..<150).map { "driver-\($0)" }).union(["", "bad/path", String(repeating: "x", count: 129)])
        coordinator.setRoster(viewerID: "viewer", drawnUIDs: uids)
        await wait { repository.opened.count == LiveClubBadgeCoordinator.maximumProfiles }
        coordinator.setRoster(viewerID: "viewer", drawnUIDs: uids)
        XCTAssertEqual(repository.opened.count, 100)
        XCTAssertFalse(repository.opened.contains("bad/path"))
        coordinator.stop()
        await wait { repository.cancelled.count == 100 }
    }

    func testTerminatedProfileStreamClearsItsLastBadge() async throws {
        let repository = FakeRepository()
        let coordinator = LiveClubBadgeCoordinator { _ in repository }
        coordinator.setRoster(viewerID: "viewer", drawnUIDs: ["driver"])
        await wait { repository.opened == ["driver"] }
        repository.emit(try XCTUnwrap(LiveClubBadge.decode(fields(logo))), uid: "driver")
        await wait { !coordinator.badges.isEmpty }
        repository.streams["driver"]?.finish()
        await wait { coordinator.badges.isEmpty }
        coordinator.stop()
    }

    func testBadgeRendersInSharedNearbyAndConvoyPhotoAtAllTextSizes() throws {
        let badge = try XCTUnwrap(LiveClubBadge.decode(fields(nil)))
        for diameter: CGFloat in [38, 40] {
            for size: DynamicTypeSize in [.large, .accessibility5] {
                let host = UIHostingController(rootView: LiveCarPhotoBadge(
                    imageURL: nil, badge: badge, diameter: diameter
                ).environment(\.dynamicTypeSize, size))
                host.loadViewIfNeeded()
                let measured = host.sizeThatFits(in: CGSize(width: 100, height: 100))
                XCTAssertEqual(measured.width, diameter, accuracy: 0.5)
                XCTAssertEqual(measured.height, diameter, accuracy: 0.5)
                let renderer = ImageRenderer(content: LiveCarPhotoBadge(
                    imageURL: nil, badge: badge, diameter: diameter
                ).padding(4).environment(\.dynamicTypeSize, size))
                let image = try XCTUnwrap(renderer.uiImage)
                let withoutBadge = ImageRenderer(content: LiveCarPhotoBadge(
                    imageURL: nil, badge: nil, diameter: diameter
                ).padding(4).environment(\.dynamicTypeSize, size))
                XCTAssertNotEqual(image.pngData(), withoutBadge.uiImage?.pngData(),
                                  "Affiliation must remain visible above the clipped photo")
            }
        }
    }

    private func fields(_ url: String?) -> [String: Any] {
        ["clubId": "test-club", "clubName": "Test Club", "logoUrl": url as Any? ?? NSNull()]
    }

    private func wait(until condition: @escaping @MainActor () -> Bool) async {
        let deadline = ContinuousClock.now.advanced(by: .seconds(3))
        while !condition(), ContinuousClock.now < deadline {
            try? await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(condition(), "Profile stream did not settle")
    }

    private final class FakeRepository: LiveClubBadgeRepository {
        var opened = Set<String>()
        var cancelled = Set<String>()
        var streams: [String: AsyncStream<LiveClubBadge?>.Continuation] = [:]

        func badgeUpdates(uid: String) -> AsyncStream<LiveClubBadge?> {
            opened.insert(uid)
            return AsyncStream { continuation in
                streams[uid] = continuation
                continuation.onTermination = { [weak self] _ in
                    Task { @MainActor [weak self] in self?.cancelled.insert(uid) }
                }
            }
        }

        func emit(_ badge: LiveClubBadge?, uid: String) { streams[uid]?.yield(badge) }
    }
}

@MainActor
final class LiveDriverAppearanceTests: XCTestCase {
    func testRingVisibilityClearsImmediatelyWithRosterAndViewer() async {
        let repository = AppearanceRepository()
        let coordinator = LiveClubBadgeCoordinator { _ in repository }
        coordinator.setRoster(viewerID: "viewer", drawnUIDs: ["driver"])
        for _ in 0..<100 where repository.stream == nil { await Task.yield() }
        repository.stream?.yield(.init(clubBadge: nil, showMembershipMapRing: true))
        for _ in 0..<100 where !coordinator.showsMembershipRing(for: "driver", viewerID: "viewer") { await Task.yield() }
        XCTAssertTrue(coordinator.showsMembershipRing(for: "driver", viewerID: "viewer"))
        XCTAssertFalse(coordinator.showsMembershipRing(for: "driver", viewerID: "other"))
        coordinator.setRoster(viewerID: "viewer", drawnUIDs: [])
        XCTAssertFalse(coordinator.showsMembershipRing(for: "driver", viewerID: "viewer"))
        repository.stream?.yield(.init(clubBadge: nil, showMembershipMapRing: true))
        await Task.yield()
        XCTAssertTrue(coordinator.membershipRings.isEmpty)
        coordinator.stop()
    }
    func testRingUsesNativeGoldOverlayWithoutChangingPhotoSize() throws {
        let plain = ImageRenderer(content: LiveCarPhotoBadge(imageURL: nil, badge: nil).padding(4))
        let ring = ImageRenderer(content: LiveCarPhotoBadge(imageURL: nil, badge: nil, showMembershipMapRing: true).padding(4))
        XCTAssertNotEqual(try XCTUnwrap(plain.uiImage).pngData(), try XCTUnwrap(ring.uiImage).pngData())
        XCTAssertEqual(plain.uiImage?.size, ring.uiImage?.size)
    }
    private final class AppearanceRepository: LiveClubBadgeRepository {
        var stream: AsyncStream<LiveDriverAppearance?>.Continuation?
        func badgeUpdates(uid: String) -> AsyncStream<LiveClubBadge?> { AsyncStream { $0.finish() } }
        func appearanceUpdates(uid: String) -> AsyncStream<LiveDriverAppearance?> { AsyncStream { stream = $0 } }
    }

    func testPublicGoldRingEligibilityAndOptOutMirrorAndroid() {
        XCTAssertFalse(LiveDriverAppearance.decode([:])!.showMembershipMapRing)
        XCTAssertTrue(LiveDriverAppearance.decode(["membershipMapRingEligible": true])!.showMembershipMapRing)
        XCTAssertFalse(LiveDriverAppearance.decode(["membershipMapRingEligible": true, "showMembershipMapRing": false])!.showMembershipMapRing)
        XCTAssertFalse(LiveDriverAppearance.decode(["membershipMapRingEligible": false, "showMembershipMapRing": true])!.showMembershipMapRing)
        XCTAssertNil(LiveDriverAppearance.decode(nil))
    }
}
