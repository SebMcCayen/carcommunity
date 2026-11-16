import SwiftUI
import UIKit
import XCTest
@testable import KCC

@MainActor
final class MapReadingDesignTests: XCTestCase {
    func testCompassModesAreVisuallyDistinctAndOnlyNorthRotates() throws {
        for scheme in [ColorScheme.light, .dark] {
            func render(_ mode: MapCompassMode, bearing: Double) throws -> Data {
                let button = MapCompassButton(mode: mode, bearing: bearing, onModeChange: { _ in })
                    .environment(\.colorScheme, scheme)
                    .environment(\.dynamicTypeSize, .accessibility5)
                let size = measure(button, width: 48, size: .accessibility5, language: "sv")
                XCTAssertEqual(size.width, 48, accuracy: 1)
                XCTAssertEqual(size.height, 48, accuracy: 1)
                let renderer = ImageRenderer(content: button.padding(8))
                renderer.scale = 2
                let image = try XCTUnwrap(renderer.uiImage)
                let attachment = XCTAttachment(image: image)
                attachment.name = "compass-\(mode.rawValue)-\(Int(bearing))-\(scheme)"
                attachment.lifetime = .keepAlways
                add(attachment)
                return try XCTUnwrap(image.pngData())
            }
            let north = try render(.northUp, bearing: 0)
            let course = try render(.courseUp, bearing: 0)
            XCTAssertNotEqual(north, course, "The mode must be visible even while facing north or stationary")
            XCTAssertNotEqual(north, try render(.northUp, bearing: 90), "The compass must keep pointing north")
            XCTAssertEqual(course, try render(.courseUp, bearing: 90), "The course arrow must stay screen-up")
        }
    }

    func testActiveSessionControlsFitNarrowWidthAtLargestTextSize() {
        for width: CGFloat in [288, 358, 398] {
            for language in ["sv", "en"] {
                let controls = MapLiveSessionControls(
                    busy: false, failed: false, onManage: {}, onStop: {}, onHide: {}
                )
                let normal = measure(controls, width: width, size: .large, language: language)
                let large = measure(controls, width: width, size: .accessibility5, language: language)
                for result in [normal, large] {
                    XCTAssertLessThanOrEqual(result.width, width + 1)
                    XCTAssertGreaterThanOrEqual(result.height, 96)
                    XCTAssertLessThanOrEqual(result.height, 240,
                        "Session controls must leave reading/map space on a small phone")
                }
            }
        }
    }

    func testConvoyPrivacyControlsFitWithoutSingleSessionStop() {
        let controls = MapLiveSessionControls(
            busy: true, failed: true, onManage: {}, onStop: nil, onHide: {}
        )
        let result = measure(controls, width: 288, size: .accessibility5, language: "sv")
        XCTAssertLessThanOrEqual(result.width, 289)
        XCTAssertGreaterThan(result.height, 96)
        XCTAssertTrue(result.height.isFinite)
    }

    func testNearbyIdentityWrapsPermittedNameWithoutAnImageRequest() {
        let marker = LiveMarker(
            uid: "synthetic", latitude: 57, longitude: 12,
            displayName: "En testmedlem med ett mycket långt visningsnamn",
            imagePath: nil, recordedAt: nil, accuracyMeters: nil
        )
        let content = NearbySharerIdentity(marker: marker, imageURL: nil)
        let normal = measure(content, width: 256, size: .large, language: "sv")
        let large = measure(content, width: 256, size: .accessibility5, language: "sv")
        XCTAssertLessThanOrEqual(large.width, 257)
        XCTAssertGreaterThan(large.height, normal.height)
    }

    private func measure<Content: View>(
        _ content: Content, width: CGFloat, size: DynamicTypeSize, language: String
    ) -> CGSize {
        let host = UIHostingController(rootView: content
            .environment(\.locale, Locale(identifier: language))
            .environment(\.dynamicTypeSize, size)
            .environment(\.colorScheme, .dark))
        host.overrideUserInterfaceStyle = .dark
        host.loadViewIfNeeded()
        return host.sizeThatFits(in: CGSize(width: width, height: 10_000))
    }
}
