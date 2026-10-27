import SwiftUI
import UIKit
import XCTest
@testable import KCC

@MainActor
final class SocialDesignTests: XCTestCase {
    private let memberName = "En testmedlem med ett mycket långt visningsnamn"
    private let text = "Ett längre meddelande som ska vara läsbart även med största textstorleken på en liten telefon."

    func testInboxAndNotificationRowsGrowWithTextWithoutOverflow() {
        assertWrapping(ConversationRow(conversation: DmConversation(conversationId: "synthetic",
            otherUser: DmUser(uid: "peer", displayName: memberName, avatarPath: nil),
            lastMessage: DmMessagePreview(text: text, senderUid: "peer", createdAtMillis: nil),
            unreadCount: 120, lastMessageAtMillis: 1760000000000)))
        for read in [false, true] {
            assertWrapping(NotificationRow(item: AppNotification(id: "synthetic", category: .subscriptionStatus,
                title: memberName, body: text, isRead: read, createdAt: Date(timeIntervalSince1970: 1760000000))))
        }
    }

    func testFriendIdentityPointsAndActionsWrapTogether() {
        let friend = FriendSummary(uid: "peer", displayName: memberName, avatarPath: nil, friendsSince: nil)
        assertWrapping(FriendRowContent(friend: friend, points: 12345678, onMessage: {}, onProfile: {}))
        assertWrapping(FriendRowContent(friend: friend, points: 0, onMessage: nil, onProfile: nil))
        assertWrapping(SocialMemberLabel(name: "  "))
        assertWrapping(FriendRequestContent(name: memberName, busy: false, onAccept: {}, onDeclineOrCancel: {}))
        assertWrapping(FriendRequestContent(name: memberName, busy: true, onAccept: nil, onDeclineOrCancel: {}))
    }

    func testOriginalDriverPostAndCountsRemainReadableWithoutMediaRequests() {
        let strings = DriverFeedStrings { String(localized: String.LocalizationValue($0)) }
        assertWrapping(DriverPostBody(post: DriverPost(id: "synthetic", authorUid: "peer", author: memberName,
            handle: "test", text: text, createdAt: nil, mediaURLs: [], likes: 123456, replies: 1234), strings: strings))
    }

    func testMessageDeliveryAndReplyLayoutsDoNotClipLargeText() {
        for state: DmDeliveryState in [.sent, .sending, .failed] {
            for own in [false, true] {
                let message = DmMessage(id: "synthetic", senderUid: "peer", text: text,
                    createdAtMillis: nil, createdAtIso: nil, clientId: "retry-id", deliveryState: state,
                    sendError: .generic, replyTo: DmReplyTo(messageId: "quoted", senderUid: "peer",
                        senderDisplayName: memberName, textPreview: text))
                assertWrapping(MessageBubble(message: message, isOwn: own, onRetry: { _ in }))
            }
        }
    }

    func testComposerKeepsLongDraftAndSendWithinSmallViewport() {
        var draft = String(repeating: "Testmeddelande ", count: 80)
        let original = draft
        let binding = Binding(get: { draft }, set: { draft = $0 })
        for size: DynamicTypeSize in [.large, .accessibility5] {
            let result = measure(DmComposer(draft: binding, onSend: { _ in }), width: 288, size: size, language: "sv", dark: true)
            XCTAssertLessThanOrEqual(result.width, 289)
            XCTAssertGreaterThanOrEqual(result.height, 48)
            XCTAssertLessThan(result.height, 200, "Composer must leave a reading viewport above the keyboard")
        }
        XCTAssertEqual(draft, original, "A bounded input viewport must not truncate the actual draft")
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

    private func measure<Content: View>(_ view: Content, width: CGFloat, size: DynamicTypeSize, language: String, dark: Bool) -> CGSize {
        let host = UIHostingController(rootView: view.environment(\.locale, Locale(identifier: language))
            .environment(\.dynamicTypeSize, size).environment(\.colorScheme, dark ? .dark : .light))
        host.overrideUserInterfaceStyle = dark ? .dark : .light
        host.loadViewIfNeeded()
        return host.sizeThatFits(in: CGSize(width: width, height: 10000))
    }
}
