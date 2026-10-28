import SwiftUI
import UIKit
import XCTest
@testable import KCC

@MainActor
final class ConvoyDesignTests: XCTestCase {
    private let name = "En testmedlem med ett längre namn som ska visas i sin helhet"

    func testConvoyAndChatSummariesWrapTitlesStatusesAndNameFallbacks() {
        for status: ConvoyStatus in [.forming, .active, .ended] {
            let convoy = makeConvoy(status: status)
            assertWrapping(ConvoySummaryContent(convoy: convoy).kccContentCard())
            assertWrapping(ConvoySummaryContent(convoy: convoy, showsDisclosure: true).kccContentCard())
            assertWrapping(ConvoyChatRow(convoy: ChatConvoy(convoyId: "synthetic", title: name,
                status: status.rawValue, memberCount: 51)).kccContentCard())
        }
        assertWrapping(ConvoyChatRow(convoy: ChatConvoy(convoyId: "fallback", status: "active", memberCount: 4,
            memberNames: [name, "En annan testmedlem", "Tredje testmedlemmen"])).kccContentCard())
    }

    func testFriendSelectionAndPendingCommandLabelsHaveRoomAtLargeText() {
        for selected in [true, false] {
            assertWrapping(ConvoyFriendChoiceLabel(friend: FriendSummary(uid: "synthetic", displayName: name,
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
                assertWrapping(ConvoyMemberSummary(member: ConvoyMember(uid: "synthetic", displayName: name,
                    role: role, inviteStatus: status)).kccContentCard())
            }
        }
        let accepted = ConvoyMember(uid: "member", displayName: name, role: .member, inviteStatus: .accepted)
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
                participants: [ConvoyMember(uid: "synthetic", displayName: name, role: .member, inviteStatus: .accepted)],
                participantCount: 51, distanceMeters: distance)))
        }
    }

    func testGroupDriveStatusAndRosterHaveScalableTextInAllStates() {
        for status: GroupDriveStatus in [.joined, .onTheWay, .arrived, .left] {
            assertWrapping(GroupDriveParticipantRow(participant: GroupDriveParticipant(uid: "synthetic", displayName: name, status: status)))
        }
        for current: GroupDriveStatus? in [nil, .joined, .onTheWay, .arrived] {
            for busy in [true, false] {
                assertWrapping(GroupDriveStatusControls(current: current, busy: busy, onSelect: { _ in }).kccContentCard())
            }
        }
    }

    func testGroupDriveUsesOnlyProvidedEventContextIncludingMissingDateAndPlace() {
        for hasSchedule in [true, false] {
            let event = EventSummary(id: "synthetic", title: name, summary: nil,
                startsAt: hasSchedule ? Date(timeIntervalSince1970: 1_700_000_000) : nil, endsAt: nil,
                approximateArea: nil, locationName: hasSchedule ? "En plats med ett längre namn" : nil,
                latitude: nil, longitude: nil, isOfficial: false, status: .published, counts: RsvpCounts(going: 0, maybe: 0, notGoing: 0))
            assertWrapping(GroupDriveEventContext(event: event))
        }
    }

    private func makeConvoy(status: ConvoyStatus) -> ConvoyItem {
        ConvoyItem(convoyId: "synthetic", title: name, status: status,
            members: [ConvoyMember(uid: "owner", displayName: name, role: .owner, inviteStatus: .accepted)],
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
