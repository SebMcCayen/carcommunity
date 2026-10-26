import XCTest
import UIKit

/// Exercises the shipping SwiftUI shell without Firebase config or credentials.
/// A provisioned build deliberately fails the initial shell assertion: these
/// smoke tests must not sign in to or modify the production Firebase project.
@MainActor
final class ShellSmokeTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testMapBackdropRemainsVisibleAfterTabAndSettingsRoundTrip() {
        let app = launch(language: "en")
        let placeholder = app.staticTexts["shell.mapPlaceholder"]
        XCTAssertTrue(placeholder.waitForExistence(timeout: 5))
        XCTAssertTrue(placeholder.isHittable, "Map content must not be covered by the tab host")
        app.buttons["shell.tab.social"].tap()
        app.buttons["shell.tab.map"].tap()
        assertSelected(app.buttons["shell.tab.map"])
        XCTAssertTrue(placeholder.isHittable)
        openSettings(app)
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(placeholder.waitForExistence(timeout: 5))
        XCTAssertTrue(placeholder.isHittable)
    }

    func testMapToolsAppearOnRightAndWorkWithoutBackend() {
        let app = launch(language: "en")
        let layers = app.buttons["shell.mapLayers"]
        let compass = app.buttons["shell.compass"]
        XCTAssertTrue(layers.waitForExistence(timeout: 5))
        XCTAssertTrue(compass.isHittable)
        XCTAssertGreaterThan(layers.frame.midX, app.frame.midX)
        XCTAssertGreaterThan(compass.frame.minY, layers.frame.maxY)
        XCTAssertEqual(compass.value as? String, "NorthUp")
        compass.tap()
        let courseUp = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == %@", "CourseUp"), object: compass
        )
        XCTAssertEqual(XCTWaiter.wait(for: [courseUp], timeout: 5), .completed)
        compass.tap()
        let northUp = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == %@", "NorthUp"), object: compass
        )
        XCTAssertEqual(XCTWaiter.wait(for: [northUp], timeout: 5), .completed)
        layers.tap()
        XCTAssertTrue(app.navigationBars["Map layers"].waitForExistence(timeout: 5))
    }

    func testMapLayersAppearanceChoicesAndFocusControl() {
        let app = launch(language: "en")
        app.buttons["shell.mapLayers"].tap()
        XCTAssertTrue(app.navigationBars["Map layers"].waitForExistence(timeout: 5))
        let scroll = app.scrollViews.firstMatch
        for id in ["night", "day", "system"] {
            let choice = app.buttons["shell.layersAppearance.\(id)"]
            for _ in 0..<4 where !choice.isHittable { scroll.swipeUp() }
            XCTAssertTrue(choice.isHittable)
            choice.tap()
            assertSelected(choice)
        }
        let expand = app.buttons["Show map focus distance"]
        for _ in 0..<4 where !expand.isHittable { scroll.swipeUp() }
        XCTAssertTrue(expand.isHittable)
        expand.tap()
        // Expansion increases the scrollable content; the slider can be below
        // the medium detent and absent from the accessibility snapshot until scrolled.
        let zoom = app.sliders["shell.layersZoom"]
        for _ in 0..<4 where !zoom.isHittable { scroll.swipeUp() }
        XCTAssertTrue(zoom.waitForExistence(timeout: 5))
        XCTAssertTrue(zoom.isHittable)
        app.buttons["Close map layers"].tap()
        app.buttons["shell.mapLayers"].tap()
        let system = app.buttons["shell.layersAppearance.system"]
        for _ in 0..<4 where !system.isHittable { app.scrollViews.firstMatch.swipeUp() }
        assertSelected(system)
    }

    func testSwedishMapLayersWithAccessibilityText() {
        let app = launch(language: "sv", largeText: true, theme: "Dark")
        app.buttons["shell.mapLayers"].tap()
        XCTAssertTrue(app.navigationBars["Kartlager"].waitForExistence(timeout: 5))
        let scroll = app.scrollViews.firstMatch
        for (id, label) in [("day", "Dag"), ("night", "Natt"), ("system", "System")] {
            let choice = app.buttons["shell.layersAppearance.\(id)"]
            for _ in 0..<8 where !choice.isHittable { scroll.swipeUp() }
            XCTAssertTrue(choice.isHittable)
            XCTAssertEqual(choice.label, label)
            XCTAssertGreaterThanOrEqual(choice.frame.height, 48)
            choice.tap()
            assertSelected(choice)
        }
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "map-layers-sv-dark-accessibility"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        XCTAssertTrue(app.buttons["Stäng kartlager"].isHittable)
        app.buttons["Stäng kartlager"].tap()
        XCTAssertTrue(app.buttons["shell.mapLayers"].waitForExistence(timeout: 5))
    }

    func testSwedishShellNavigation() {
        checkShell(language: "sv", labels: ["Karta", "Chatt", "Social", "Garage"])
    }

    func testEnglishShellNavigation() {
        checkShell(language: "en", labels: ["Map", "Chat", "Social", "Garage"])
    }

    func testSettingsAndNestedBackNavigation() {
        let app = launch(language: "en")
        openSettings(app)
        let privacy = element("settings.privacyPolicy", in: app)
        let terms = element("settings.terms", in: app)
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
        XCTAssertTrue(app.buttons["shell.tab.map"].isSelected)
    }

    func testSettingsControlsRemainAccessibleWithLargeText() {
        let app = launch(language: "en", largeText: true)
        openSettings(app)
        // These are accessibility-tree smoke checks, not a full VoiceOver audit.
        for id in ["settings.privacyPolicy", "settings.terms"] {
            let button = element(id, in: app)
            // SwiftUI List virtualizes rows. At accessibility XXXL, a single
            // swipe need not instantiate the legal section below account rows.
            for _ in 0..<6 {
                if button.exists && button.isHittable { break }
                app.swipeUp()
            }
            XCTAssertTrue(button.waitForExistence(timeout: 5))
            XCTAssertFalse(button.label.isEmpty)
            XCTAssertTrue(button.isHittable, "\(id) must remain reachable with large text")
        }
        let back = app.buttons["Back"].firstMatch
        XCTAssertTrue(back.isHittable)
        back.tap()
        XCTAssertTrue(app.buttons["shell.menu"].waitForExistence(timeout: 5))
    }

    func testChatChromeAndRecoveryRemainReadableAtAccessibilityXXXL() {
        let app = launch(language: "en", largeText: true, theme: "Dark")
        // Geometry, not just existence: the previous render had hittable tabs
        // while their labels wrapped into a roughly 200-point navigation bar.
        for id in ["map", "chat", "create", "social", "garage"] {
            let tab = app.buttons["shell.tab.\(id)"]
            XCTAssertTrue(tab.isHittable)
            XCTAssertFalse(tab.label.isEmpty)
            XCTAssertGreaterThanOrEqual(tab.frame.height, 44)
            XCTAssertLessThanOrEqual(tab.frame.height, 64)
            XCTAssertGreaterThanOrEqual(tab.frame.width, 44)
            XCTAssertTrue(app.frame.contains(tab.frame))
        }
        for id in ["social", "garage", "map", "chat"] {
            let tab = app.buttons["shell.tab.\(id)"]
            tab.tap()
            if id == "chat" {
                // The first cold chat render can outlive XCUITest's idle wait.
                // Wait for the actual destination, without retrying the tap.
                XCTAssertTrue(element("chatHub.opaqueScreen", in: app).waitForExistence(timeout: 15))
            }
            assertSelected(tab)
        }
        for id in ["driver", "chats"] {
            let source = app.buttons["chatHub.source.\(id)"]
            XCTAssertTrue(source.isHittable)
            XCTAssertGreaterThanOrEqual(source.frame.height, 44)
            XCTAssertLessThanOrEqual(source.frame.height, 80)
            source.tap()
            assertSelected(source)
        }

        let message = app.staticTexts["chatHub.unavailable.message"]
        XCTAssertTrue(message.waitForExistence(timeout: 5))
        XCTAssertEqual(message.label, "This isn't available right now.")
        // Independently estimate full, uncapped body text height. The old
        // accessibility label contained all words even when pixels ellipsized.
        let font = UIFont.preferredFont(forTextStyle: .body, compatibleWith:
            UITraitCollection(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge))
        let fullText = (message.label as NSString).boundingRect(
            with: CGSize(width: message.frame.width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: font], context: nil)
        XCTAssertGreaterThan(message.frame.height, 90, "Reading text must not inherit the chrome size cap")
        XCTAssertGreaterThanOrEqual(message.frame.height, fullText.height - font.lineHeight * 0.25,
            "The recovery message must reserve its full rendered line height")
        let recovery = element("chatHub.unavailable.scroll", in: app)
        XCTAssertTrue(recovery.exists)
        recovery.swipeUp()
        XCTAssertLessThanOrEqual(message.frame.maxY, recovery.frame.maxY + 1)
        XCTAssertGreaterThan(message.frame.maxY, recovery.frame.minY)
        attach(app, name: "configless-en-Dark-AXXXL-S29-readable-recovery")

        let channels = element("chatHub.channels", in: app)
        XCTAssertLessThanOrEqual(channels.frame.height, 110)
        // Notifications is intentionally reached by horizontal scrolling, not
        // by removing or shrinking away one of the existing channel routes.
        for index in 0..<4 {
            let channel = app.buttons["chatHub.channel.\(index)"]
            for _ in 0..<4 {
                if channel.isHittable && channels.frame.contains(channel.frame) { break }
                channels.swipeLeft()
            }
            XCTAssertTrue(channel.isHittable)
            XCTAssertTrue(channels.frame.contains(channel.frame))
            channel.tap()
            assertSelected(channel)
        }
        attach(app, name: "configless-en-Dark-AXXXL-S29-notifications-reachable")
        app.buttons["shell.tab.create"].tap()
        assertSelected(app.buttons["shell.tab.map"])
    }

    private func checkShell(language: String, labels: [String]) {
        let app = launch(language: language)
        let ids = ["map", "chat", "social", "garage"]
        assertSelected(app.buttons["shell.tab.map"])
        for (index, label) in labels.enumerated().dropFirst() {
            let tab = app.buttons["shell.tab.\(ids[index])"]
            XCTAssertTrue(tab.isHittable)
            XCTAssertEqual(tab.label, label)
            tab.tap()
            assertSelected(tab)
            if index == 2 {
                let social = element("shell.socialHub", in: app)
                XCTAssertTrue(social.waitForExistence(timeout: 5))
                let socialLabels = language == "sv"
                    ? ["Event", "Kronjakt", "Topplista"]
                    : ["Events", "Crown Hunt", "Leaderboard"]
                for socialLabel in socialLabels {
                    XCTAssertTrue(social.buttons[socialLabel].waitForExistence(timeout: 5))
                }
                XCTAssertFalse(social.buttons[language == "sv" ? "Konvojer" : "Convoys"].exists,
                               "Convoys must not be offered in the Social menu")
                // Partners remains hidden without its Firebase-backed coordinator.
                XCTAssertFalse(social.buttons["Partners"].exists)
                attach(app, name: "configless-\(language)-Social-menu")
            }
            if index == 1 {
                XCTAssertTrue(element("chatHub.opaqueScreen", in: app).waitForExistence(timeout: 5))
                XCTAssertFalse(app.buttons["shell.search"].exists)
            } else {
                let heading = index == 3 ? (language == "sv" ? "Mitt garage" : "My Garage") : label
                XCTAssertTrue(app.staticTexts[heading].firstMatch.waitForExistence(timeout: 5))
            }
        }
        app.buttons["shell.tab.map"].tap()
        assertSelected(app.buttons["shell.tab.map"])
        XCTAssertTrue(app.buttons["shell.menu"].isHittable)
    }

    func testRedesignScreenshotsEnglishLight() {
        captureRedesign(language: "en", theme: "Light")
    }

    func testRedesignScreenshotsSwedishDark() {
        captureRedesign(language: "sv", theme: "Dark")
    }

    func testRedesignScreenshotsEnglishDarkLargeText() {
        captureRedesign(language: "en", theme: "Dark", largeText: true)
    }

    private func captureRedesign(language: String, theme: String, largeText: Bool = false) {
        let app = launch(language: language, largeText: largeText, theme: theme)
        let prefix = "configless-\(language)-\(theme)-\(largeText ? "AXXXL" : "default")"
        let map = app.staticTexts["shell.mapPlaceholder"]
        XCTAssertTrue(map.waitForExistence(timeout: 5))
        let mapIdentity = map.value as? String
        XCTAssertNotNil(mapIdentity)
        XCTAssertTrue(mapIdentity?.hasSuffix("|\(theme)") == true,
            "Capture names must match the actual SwiftUI color scheme")
        XCTAssertFalse(app.textFields["addressSearch.query"].exists)
        XCTAssertTrue(app.buttons["shell.search"].isHittable)
        let layers = app.buttons["shell.mapLayers"]
        let avatar = app.buttons["shell.menu"]
        XCTAssertTrue(layers.isHittable)
        XCTAssertGreaterThanOrEqual(layers.frame.minY, avatar.frame.maxY)
        XCTAssertLessThanOrEqual(layers.frame.minY, avatar.frame.maxY + 32,
            "S01 rail must start just below top chrome, not at map center")
        attach(app, name: "\(prefix)-S01-map-collapsed-search")

        app.buttons["shell.search"].tap()
        let field = app.textFields["addressSearch.query"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        field.typeText("Kungsbacka")
        attach(app, name: "\(prefix)-S02-search-keyboard")
        app.buttons["addressSearch.close"].tap()
        XCTAssertTrue(app.buttons["shell.search"].waitForExistence(timeout: 5))
        XCTAssertFalse(field.exists)

        for _ in 0..<2 {
            app.buttons["shell.tab.chat"].tap()
            assertSelected(app.buttons["shell.tab.chat"])
            XCTAssertTrue(element("chatHub.opaqueScreen", in: app).waitForExistence(timeout: 5))
            XCTAssertTrue(app.navigationBars[language == "sv" ? "Chatt" : "Chat"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.buttons["shell.search"].exists)
            attach(app, name: "\(prefix)-S29-chat-opaque")
            let driver = app.buttons["chatHub.source.driver"]
            XCTAssertTrue(driver.waitForExistence(timeout: 5))
            XCTAssertTrue(driver.isHittable)
            driver.tap()
            assertSelected(driver)
            attach(app, name: "\(prefix)-S37-driver-restricted-configless")
            app.buttons["chatHub.source.chats"].tap()
            app.buttons["shell.tab.map"].tap()
            assertSelected(app.buttons["shell.tab.map"])
            XCTAssertEqual(map.value as? String, mapIdentity, "Tab changes must retain the map host")
        }

        app.buttons["shell.menu"].tap()
        XCTAssertTrue(app.buttons["shell.myDrives"].waitForExistence(timeout: 5))
        attach(app, name: "\(prefix)-S61-my-drives-entry")
        app.buttons["shell.myDrives"].tap()
        XCTAssertTrue(app.buttons["myDrives.stats"].waitForExistence(timeout: 5))
        attach(app, name: "\(prefix)-S53-my-drives-unavailable")
        app.buttons[language == "sv" ? "Tillbaka" : "Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["profile.myDrives"].waitForExistence(timeout: 5))
        app.buttons[language == "sv" ? "Tillbaka" : "Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["shell.menu"].waitForExistence(timeout: 5))
        XCTAssertEqual(map.value as? String, mapIdentity)
        openSettings(app, language: language)
        attach(app, name: "\(prefix)-S69-settings")
    }

    private func attach(_ app: XCUIApplication, name: String) {
        // Reached only after launch verifies the config-less Settings identity.
        // Every capture is an actual simulator render; no production fixtures.
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func launch(language: String, largeText: Bool = false, theme: String = "Light") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(\(language))", "-AppleLocale", language]
        app.launchArguments += ["-KCCUITestTheme", theme]
        // UIKit can retain a previous launch's preferred category. Reset it
        // explicitly so the Swedish scenario does not inherit the large-text one.
        app.launchArguments += [
            "-UIPreferredContentSizeCategoryName",
            largeText ? "UICTContentSizeCategoryAccessibilityXXXL" : "UICTContentSizeCategoryL"
        ]
        app.launch()
        XCTAssertTrue(
            app.buttons["shell.menu"].waitForExistence(timeout: 15),
            "Build without GoogleService-Info.plist to test the config-less shell"
        )
        XCTAssertEqual(app.buttons["shell.menu"].label, language == "sv" ? "Inställningar" : "Settings")
        return app
    }

    private func assertSelected(_ tab: XCUIElement) {
        // A tap returning does not guarantee SwiftUI has published the next
        // accessibility snapshot. Wait for the actual selection, without retaps.
        let selected = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "selected == true"),
            object: tab
        )
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 5), .completed,
            "Selection did not settle for \(tab.identifier)")
    }

    private func element(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        // SwiftUI Link can expose a link or button trait across iOS versions.
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    private func openSettings(_ app: XCUIApplication, language: String = "en") {
        app.buttons["shell.menu"].tap()
        let settings = app.buttons["shell.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        settings.tap()
        XCTAssertTrue(app.navigationBars[language == "sv" ? "Inställningar" : "Settings"].waitForExistence(timeout: 5))
    }
}
