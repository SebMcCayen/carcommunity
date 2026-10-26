import SwiftUI
import UIKit
import XCTest

@testable import KCC

/// Native SwiftUI layout regressions using synthetic content only. These check
/// wrapping at narrow widths, not screenshot similarity or backend integration.
@MainActor
final class FeatureDesignLayoutTests: XCTestCase {
    func testGarageCardKeepsLongVehicleDetailsReadable() throws {
        let vehicle = try XCTUnwrap(Vehicle.fromMap(id: "layout-car", map: [
            "make": "Volvo", "model": "V60 Cross Country med längre modellnamn",
            "modelYear": 2020, "powertrain": "hybrid", "isMainCar": true,
            "engineDescription": "Lokal layoutkontroll med en längre motorbeskrivning",
        ]))
        assertWrapping(GarageVehicleCard(vehicle: vehicle, imageURL: nil))
    }

    func testEventCardWrapsTitleDateAndPublicLocation() {
        let event = EventSummary(
            id: "layout-event", title: "Kvällsträff med communityn och ett längre eventnamn",
            summary: nil, startsAt: Date(timeIntervalSince1970: 1_800_000_000), endsAt: nil,
            approximateArea: "Kungsbacka", locationName: "En längre offentlig platsbeskrivning",
            latitude: nil, longitude: nil, isOfficial: true, status: .published,
            counts: RsvpCounts(going: 12, maybe: 3, notGoing: 1)
        )
        assertWrapping(EventRow(event: event))
    }

    func testOwnAndOtherChatMessagesGrowWithAccessibilityText() {
        let message = ChannelMessage(
            id: "layout-message", senderUid: "synthetic-sender",
            text: "Var samlas vi inför kvällens runda? Vi tar en lugn tur tillsammans.",
            senderDisplayName: "Testmedlem med ett längre visningsnamn"
        )
        assertWrapping(MessageRow(message: message, isOwn: false))
        assertWrapping(MessageRow(message: message, isOwn: true))
    }

    func testProfilePointsCardKeepsItsRecoveryAndLedgerCopyReadable() {
        assertWrapping(ProfilePointsSection(balance: 120, recentEarnings: [], onOpenLedger: {}))
    }

    private func assertWrapping<Content: View>(
        _ content: Content, file: StaticString = #filePath, line: UInt = #line
    ) {
        for (language, style) in [("sv", UIUserInterfaceStyle.dark), ("en", .light)] {
            for width: CGFloat in [320, 390] {
                let normal = measure(content, width: width, size: .large, language: language, style: style)
                let large = measure(content, width: width, size: .accessibility3, language: language, style: style)
                for result in [normal, large] {
                    XCTAssertTrue(result.width.isFinite && result.height.isFinite, file: file, line: line)
                    XCTAssertGreaterThan(result.height, 0, file: file, line: line)
                    XCTAssertLessThanOrEqual(result.width, width + 1, file: file, line: line)
                }
                XCTAssertGreaterThan(large.height, normal.height, file: file, line: line)
            }
        }
    }

    private func measure<Content: View>(
        _ content: Content, width: CGFloat, size: DynamicTypeSize,
        language: String, style: UIUserInterfaceStyle
    ) -> CGSize {
        let host = UIHostingController(rootView: content
            .environment(\.locale, Locale(identifier: language))
            .environment(\.dynamicTypeSize, size)
            .environment(\.colorScheme, style == .dark ? .dark : .light))
        host.overrideUserInterfaceStyle = style
        host.loadViewIfNeeded()
        return host.sizeThatFits(in: CGSize(width: width, height: 10_000))
    }
}
