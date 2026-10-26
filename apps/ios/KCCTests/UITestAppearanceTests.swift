import SwiftUI
import XCTest

@testable import KCC

#if DEBUG
final class UITestAppearanceTests: XCTestCase {
    func testTextFixturesMatchTheRequestedUncappedSizes() {
        XCTAssertEqual(ConfiglessUITestSizing.override(
            arguments: ["KCC", "-KCCUITestTextSize", "AccessibilityXXXL"], firebaseConfigured: false
        ), .accessibility5)
        XCTAssertEqual(ConfiglessUITestSizing.override(
            arguments: ["KCC", "-KCCUITestTextSize", "Large"], firebaseConfigured: false
        ), .large)
    }

    func testConfiguredAppNeverAcceptsTheFixture() {
        XCTAssertNil(ConfiglessUITestSizing.override(
            arguments: ["KCC", "-KCCUITestTextSize", "AccessibilityXXXL"], firebaseConfigured: true
        ))
    }

    func testMissingOrUnsupportedArgumentsKeepSystemBehavior() {
        for arguments in [["KCC"], ["KCC", "-KCCUITestTextSize"],
                          ["KCC", "-KCCUITestTextSize", "Unknown"]] {
            XCTAssertNil(ConfiglessUITestSizing.override(arguments: arguments, firebaseConfigured: false))
        }
    }
}
#endif
