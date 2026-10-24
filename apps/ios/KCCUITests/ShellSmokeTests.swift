import XCTest

/// Exercises the shipping SwiftUI shell without Firebase config or credentials.
/// A provisioned build deliberately fails the initial shell assertion: these
/// smoke tests must not sign in to or modify the production Firebase project.
@MainActor
final class ShellSmokeTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testSwedishShellNavigation() {
        checkShell(language: "sv", labels: ["Karta", "Historik", "Socialt", "Garage"])
    }

    func testEnglishShellNavigation() {
        checkShell(language: "en", labels: ["Map", "History", "Social", "Garage"])
    }

    func testSettingsAndNestedBackNavigation() {
        let app = launch(language: "en")
        openSettings(app)
        let privacy = app.buttons["settings.privacyPolicy"]
        let terms = app.buttons["settings.terms"]
        XCTAssertTrue(privacy.waitForExistence(timeout: 5))
        XCTAssertEqual(privacy.label, "Privacy Policy")
        XCTAssertEqual(terms.label, "Terms of Service")
        // Backend-only account actions must stay absent in this config-less build.
        XCTAssertFalse(app.buttons["settings.accountDeletion"].exists)
        XCTAssertFalse(app.buttons["settings.notificationSettings"].exists)

        let whatsNew = app.buttons["settings.whatsNew"]
        XCTAssertTrue(whatsNew.waitForExistence(timeout: 5))
        whatsNew.tap()
        XCTAssertTrue(app.navigationBars["What’s new"].waitForExistence(timeout: 5))
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(privacy.waitForExistence(timeout: 5), "Back must return to Settings")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["shell.menu"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.tabBars.buttons["Map"].isSelected)
    }

    func testSettingsControlsRemainAccessibleWithLargeText() {
        let app = launch(language: "en", largeText: true)
        openSettings(app)
        // These are accessibility-tree smoke checks, not a full VoiceOver audit.
        for id in ["settings.privacyPolicy", "settings.terms"] {
            let button = app.buttons[id]
            if !button.isHittable { app.swipeUp() }
            XCTAssertTrue(button.waitForExistence(timeout: 5))
            XCTAssertFalse(button.label.isEmpty)
            XCTAssertTrue(button.isHittable, "\(id) must remain reachable with large text")
        }
        let back = app.buttons["Back"].firstMatch
        XCTAssertTrue(back.isHittable)
        back.tap()
        XCTAssertTrue(app.buttons["shell.menu"].waitForExistence(timeout: 5))
    }

    private func checkShell(language: String, labels: [String]) {
        let app = launch(language: language)
        let tabs = app.tabBars
        XCTAssertTrue(tabs.buttons[labels[0]].isSelected, "Map is the initial tab")
        for (index, label) in labels.enumerated().dropFirst() {
            let tab = tabs.buttons[label]
            XCTAssertTrue(tab.isHittable)
            tab.tap()
            XCTAssertTrue(tab.isSelected)
            let heading = index == 3 ? (language == "sv" ? "Mitt garage" : "My Garage") : label
            XCTAssertTrue(app.staticTexts[heading].firstMatch.waitForExistence(timeout: 5))
        }
        tabs.buttons[labels[0]].tap()
        XCTAssertTrue(tabs.buttons[labels[0]].isSelected)
        XCTAssertTrue(app.buttons["shell.menu"].isHittable)
    }

    private func launch(language: String, largeText: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(\(language))", "-AppleLocale", language]
        if largeText {
            app.launchArguments += [
                "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"
            ]
        }
        app.launch()
        XCTAssertTrue(
            app.buttons["shell.menu"].waitForExistence(timeout: 15),
            "Build without GoogleService-Info.plist to test the config-less shell"
        )
        return app
    }

    private func openSettings(_ app: XCUIApplication) {
        app.buttons["shell.menu"].tap()
        let settings = app.buttons["shell.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        settings.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
    }
}
