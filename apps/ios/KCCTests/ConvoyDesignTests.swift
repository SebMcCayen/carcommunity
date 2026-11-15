import SwiftUI
import UIKit
import XCTest
@testable import KCC

@MainActor
final class ConvoyDesignTests: XCTestCase {
    private let longName = "En testmedlem med ett längre namn som ska visas i sin helhet"

    func testConvoyAndChatSummariesWrapTitlesStatusesAndNameFallbacks() {
        for status: ConvoyStatus in [.forming, .active, .ended] {
            let convoy = makeConvoy(status: status)
            assertWrapping(ConvoySummaryContent(convoy: convoy).kccContentCard())
            assertWrapping(ConvoySummaryContent(convoy: convoy, showsDisclosure: true).kccContentCard())
            assertWrapping(ConvoyChatRow(convoy: ChatConvoy(convoyId: "synthetic", title: longName,
                status: status.rawValue, memberCount: 51)).kccContentCard())
        }
        assertWrapping(ConvoyChatRow(convoy: ChatConvoy(convoyId: "fallback", status: "active", memberCount: 4,
            memberNames: [longName, "En annan testmedlem", "Tredje testmedlemmen"])).kccContentCard())
    }

    func testFriendSelectionAndPendingCommandLabelsHaveRoomAtLargeText() {
        for selected in [true, false] {
            assertWrapping(ConvoyFriendChoiceLabel(friend: FriendSummary(uid: "synthetic", displayName: longName,
                avatarPath: nil, friendsSince: nil), selected: selected))
        }
        for title: LocalizedStringKey in ["convoy.createSubmit", "convoy.inviteSubmit", "convoy.barLeaveEnds"] {
            for working in [true, false] {
                assertWrapping(ConvoyCommandLabel(title: title, working: working))
            }
        }
        for title: LocalizedStringKey in ["convoy.createSubmit", "convoy.inviteSubmit"] {
            let footer = Button {} label: { ConvoyCommandLabel(title: title, working: true) }
                .kccPrimaryAction().padding(KccSpacing.s4)
            let size = measure(footer, width: 320, size: .accessibility5)
            XCTAssertLessThanOrEqual(size.width, 321)
            XCTAssertGreaterThanOrEqual(size.height, 48)
            XCTAssertLessThan(size.height, 240, "The pinned command must leave room for the reading list")
        }
    }

    func testMemberCardsWrapOwnerInvitedDeclinedAndUnavailableLocation() {
        for role: ConvoyRole in [.owner, .member] {
            for status: ConvoyInviteStatus in [.invited, .accepted, .declined] {
                assertWrapping(ConvoyMemberSummary(member: ConvoyMember(uid: "synthetic", displayName: longName,
                    role: role, inviteStatus: status)).kccContentCard())
            }
        }
        let accepted = ConvoyMember(uid: "member", displayName: longName, role: .member, inviteStatus: .accepted)
        for available in [false, true] {
            assertWrapping(ConvoyMemberActionCard(member: accepted, waiting: false, locationAvailable: available,
                onProfile: {}, onCenter: {}))
        }
        assertWrapping(ConvoyMemberActionCard(member: ConvoyMember(uid: "pending", displayName: nil,
            role: .member, inviteStatus: .invited), waiting: true, locationAvailable: false, onProfile: nil, onCenter: nil))
        assertWrapping(ConvoyActionFeedback(error: .membershipUncertain, onDismiss: {}))
    }

    func testEndedRecapKeepsMissingDistanceDistinctFromActualZero() {
        for distance: Double? in [nil, 0, 12345] {
            assertWrapping(ConvoyRecapCard(recap: ConvoyRecapState(durationSeconds: 3840,
                participants: [ConvoyMember(uid: "synthetic", displayName: longName, role: .member, inviteStatus: .accepted)],
                participantCount: 51, distanceMeters: distance)))
        }
    }

    func testGroupDriveStatusAndRosterHaveScalableTextInAllStates() {
        for status: GroupDriveStatus in [.joined, .onTheWay, .arrived, .left] {
            assertWrapping(GroupDriveParticipantRow(participant: GroupDriveParticipant(uid: "synthetic", displayName: longName, status: status)))
        }
        for current: GroupDriveStatus? in [nil, .joined, .onTheWay, .arrived] {
            for busy in [true, false] {
                assertWrapping(GroupDriveStatusControls(current: current, busy: busy, onSelect: { _ in }).kccContentCard())
            }
        }
    }

    func testGroupDriveUsesOnlyProvidedEventContextIncludingMissingDateAndPlace() {
        for hasSchedule in [true, false] {
            let event = EventSummary(id: "synthetic", title: longName, summary: nil,
                startsAt: hasSchedule ? Date(timeIntervalSince1970: 1_700_000_000) : nil, endsAt: nil,
                approximateArea: nil, locationName: hasSchedule ? "En plats med ett längre namn" : nil,
                latitude: nil, longitude: nil, isOfficial: false, status: .published, counts: RsvpCounts(going: 0, maybe: 0, notGoing: 0))
            assertWrapping(GroupDriveEventContext(event: event))
        }
    }

    func testReactionButtonStatesRenderAtNarrowAndRegularWidthsInBothThemes() async throws {
        for width: CGFloat in [280, 390] {
            for dark in [true, false] {
                for state in ["ready", "cooldown", "active", "pending"] {
                    let row = HStack(spacing: KccSpacing.s2) {
                        ConvoyReactionButtonFace(kind: .police, ready: state != "cooldown", active: false,
                            pending: false, remainingFraction: state == "cooldown" ? 0.6 : 0)
                        ConvoyReactionButtonFace(kind: .hello, ready: true, active: false,
                            pending: false, remainingFraction: 0)
                        ConvoyReactionButtonFace(kind: .followMe, ready: state != "pending", active: state == "active",
                            pending: state == "pending", remainingFraction: 0)
                    }
                    .padding(.horizontal, KccSpacing.s3)
                    .padding(.vertical, KccSpacing.s3)
                    .frame(width: width)
                    .environment(\.colorScheme, dark ? .dark : .light)
                    .environment(\.dynamicTypeSize, .accessibility5)
                    let image = try await captureReactionRow(row, width: width, dark: dark)
                    XCTAssertEqual(image.size.width, width, accuracy: 1)
                    XCTAssertEqual(image.size.height, 80, accuracy: 1,
                        "Icon-only controls keep 56pt touch height with 12pt outer padding at maximum type size")
                    let attachment = XCTAttachment(image: image)
                    attachment.name = "convoy-reactions-\(Int(width))-\(dark ? "dark" : "light")-\(state)"
                    attachment.lifetime = .keepAlways
                    add(attachment)
                }
            }
        }
    }

    /// ImageRenderer omits UIKit-backed views such as the pending ProgressView.
    /// Capture a hosted native hierarchy so the evidence includes the real spinner.
    private func captureReactionRow<Content: View>(_ row: Content, width: CGFloat, dark: Bool) async throws -> UIImage {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previous = scene.windows.first(where: \.isKeyWindow)
        let viewport = CGSize(width: width, height: 80)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: viewport)
        window.overrideUserInterfaceStyle = dark ? .dark : .light
        window.backgroundColor = .clear
        let host = UIHostingController(rootView: row)
        host.safeAreaRegions = []
        host.view.backgroundColor = .clear
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previous?.makeKeyAndVisible()
        }
        try await Task.sleep(for: .milliseconds(250))
        host.view.layoutIfNeeded()
        XCTAssertEqual(host.view.bounds.width, viewport.width, accuracy: 1)
        XCTAssertEqual(host.view.bounds.height, viewport.height, accuracy: 1)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        format.opaque = false
        return UIGraphicsImageRenderer(size: viewport, format: format).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true),
                "Native reaction evidence must include UIKit-backed pending indicators")
        }
    }

    private func makeConvoy(status: ConvoyStatus) -> ConvoyItem {
        ConvoyItem(convoyId: "synthetic", title: longName, status: status,
            members: [ConvoyMember(uid: "owner", displayName: longName, role: .owner, inviteStatus: .accepted)],
            viewer: ConvoyViewer(inviteStatus: .accepted, role: .owner), createdAt: nil)
    }

    private func assertWrapping<Content: View>(_ view: Content, file: StaticString = #filePath, line: UInt = #line) {
        for (language, dark) in [("sv", true), ("en", false)] {
            for width: CGFloat in [288, 358] {
                let normal = measure(view, width: width, size: .large, language: language, dark: dark)
                let large = measure(view, width: width, size: .accessibility5, language: language, dark: dark)
                XCTAssertLessThanOrEqual(large.width, width + 1, file: file, line: line)
                XCTAssertGreaterThan(large.height, normal.height, file: file, line: line)
                XCTAssertTrue(large.height.isFinite, file: file, line: line)
            }
        }
    }

    private func measure<Content: View>(_ view: Content, width: CGFloat, size: DynamicTypeSize,
        language: String = "sv", dark: Bool = true) -> CGSize {
        let host = UIHostingController(rootView: view.environment(\.locale, Locale(identifier: language))
            .environment(\.dynamicTypeSize, size).environment(\.colorScheme, dark ? .dark : .light))
        host.overrideUserInterfaceStyle = dark ? .dark : .light
        host.loadViewIfNeeded()
        return host.sizeThatFits(in: CGSize(width: width, height: 10000))
    }
}
