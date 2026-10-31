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

    func testSavedPlacesDockKeepsCloseToolsAndPrimaryNavigationReachable() {
        let app = launch(language: "en", largeText: true, theme: "Dark")
        let mapIdentity = app.staticTexts["shell.mapPlaceholder"].value as? String
        XCTAssertNotNil(mapIdentity)
        let saved = app.buttons["shell.savedPlaces"]
        XCTAssertTrue(saved.waitForExistence(timeout: 15))
        saved.tap()
        let panel = element("map.reading.panel", in: app)
        let close = app.buttons["map.reading.close"]
        XCTAssertTrue(panel.waitForExistence(timeout: 5))
        XCTAssertTrue(close.isHittable)
        // CGRect conversion can report 48 as 47.99999999999994.
        XCTAssertGreaterThanOrEqual(close.frame.height + 0.001, 48)
        XCTAssertLessThanOrEqual(panel.frame.maxY, app.buttons["shell.tab.map"].frame.minY)
        XCTAssertTrue(app.buttons["shell.mapLayers"].isHittable)
        let closeFrame = close.frame
        app.scrollViews["map.reading.content"].swipeUp()
        XCTAssertTrue(close.isHittable)
        XCTAssertEqual(close.frame.minY, closeFrame.minY, accuracy: 1,
            "Close must stay pinned when the reading content scrolls")
        attach(app, name: "map-saved-places-dock-en-dark-accessibility")
        // Real OS-injected navigation tap while the panel is open, without
        // dismissing it first: a modal sheet would intercept this tap.
        app.buttons["shell.tab.chat"].tap()
        XCTAssertTrue(element("chatHub.opaqueScreen", in: app).waitForExistence(timeout: 15))
        assertSelected(app.buttons["shell.tab.chat"])
        app.buttons["shell.tab.map"].tap()
        XCTAssertFalse(panel.exists, "A reading panel must not reopen after a tab round trip")
        XCTAssertEqual(app.staticTexts["shell.mapPlaceholder"].value as? String, mapIdentity,
            "Opening/closing a dock must retain the same map host")
        saved.tap()
        XCTAssertTrue(close.waitForExistence(timeout: 5))
        close.tap()
        XCTAssertFalse(panel.exists)
        XCTAssertTrue(app.buttons["shell.mapLayers"].isHittable)
    }

    func testCrownHubTabsHistoryAndInstructionsWithLargeSwedishText() {
        let app = launch(language: "sv", largeText: true, theme: "Dark")
        app.buttons["shell.tab.social"].tap()
        let social = element("shell.socialHub", in: app)
        XCTAssertTrue(social.waitForExistence(timeout: 5))
        social.buttons["Kronjakt"].tap()
        let overview = app.buttons["crown.tab.overview"]
        let statistics = app.buttons["crown.tab.statistics"]
        XCTAssertTrue(overview.waitForExistence(timeout: 15))
        assertSelected(overview)
        XCTAssertFalse(app.buttons["crown.tab.shop"].exists, "Config-less tests cannot enable the shop")
        let tabs = app.scrollViews["crown.tabs"]
        for _ in 0..<4 {
            if statistics.isHittable { break }
            tabs.swipeLeft()
        }
        statistics.tap()
        assertSelected(statistics)
        XCTAssertTrue(element("crown.statistics", in: app).exists)
        app.buttons["crown.history.open"].tap()
        XCTAssertTrue(element("crown.history", in: app).waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(statistics.waitForExistence(timeout: 5))
        for _ in 0..<4 {
            if overview.isHittable { break }
            tabs.swipeRight()
        }
        overview.tap()
        assertSelected(overview)
        let instructions = app.buttons["crown.instructions.open"]
        for _ in 0..<6 {
            if instructions.exists && instructions.isHittable { break }
            app.scrollViews["crown.overview"].swipeUp()
        }
        XCTAssertTrue(instructions.isHittable)
        instructions.tap()
        XCTAssertTrue(element("crown.instructions", in: app).waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(overview.waitForExistence(timeout: 5))
        attach(app, name: "crown-hub-sv-dark-accessibility-configless")
        app.buttons["crown.back"].tap()
        XCTAssertTrue(social.waitForExistence(timeout: 5))
    }

    func testMapLayersAppearanceChoicesAndFocusControl() {
        let app = launch(language: "en")
        app.buttons["shell.mapLayers"].tap()
        XCTAssertTrue(app.navigationBars["Map layers"].waitForExistence(timeout: 5))
        let scroll = app.scrollViews["shell.layers.content"]
        for id in ["night", "day", "system"] {
            let choice = app.buttons["shell.layersAppearance.\(id)"]
            reveal(choice, in: scroll, minimumHeight: 48)
            XCTAssertTrue(choice.isHittable)
            choice.tap()
            assertSelected(choice)
        }
        let expand = app.buttons["Show map focus distance"]
        reveal(expand, in: scroll, minimumHeight: 48)
        XCTAssertTrue(expand.isHittable)
        expand.tap()
        XCTAssertTrue(app.buttons["Hide map focus distance"].waitForExistence(timeout: 5))
        // Expansion increases the scrollable content; the slider can be below
        // the medium detent and absent from the accessibility snapshot until scrolled.
        let zoom = app.sliders["shell.layersZoom"]
        reveal(zoom, in: scroll, minimumHeight: 0)
        XCTAssertTrue(zoom.waitForExistence(timeout: 5), "Expanded slider tree: \(app.sliders.debugDescription)")
        XCTAssertTrue(zoom.isHittable)
        app.buttons["Close map layers"].tap()
        app.buttons["shell.mapLayers"].tap()
        let system = app.buttons["shell.layersAppearance.system"]
        reveal(system, in: app.scrollViews["shell.layers.content"], minimumHeight: 48)
        assertSelected(system)
    }

    func testSwedishMapLayersWithAccessibilityText() {
        let app = launch(language: "sv", largeText: true, theme: "Dark")
        app.buttons["shell.mapLayers"].tap()
        XCTAssertTrue(app.navigationBars["Kartlager"].waitForExistence(timeout: 5))
        let scroll = app.scrollViews["shell.layers.content"]
        for (id, label) in [("day", "Dag"), ("night", "Natt"), ("system", "System")] {
            let choice = app.buttons["shell.layersAppearance.\(id)"]
            reveal(choice, in: scroll, minimumHeight: 48)
            XCTAssertTrue(choice.isHittable)
            XCTAssertEqual(choice.label, label)
            XCTAssertGreaterThanOrEqual(choice.frame.height, 48,
                "Choice \(id) frame=\(choice.frame), viewport=\(scroll.frame)")
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

    func testRedesignedFeaturePagesRetainNativeNavigationWithLargeText() {
        let app = launch(language: "en", largeText: true, theme: "Dark")
        app.buttons["shell.tab.chat"].tap()
        XCTAssertTrue(element("chatHub.opaqueScreen", in: app).waitForExistence(timeout: 5))
        attach(app, name: "feature-design-chat-en-dark-accessibility")
        app.buttons["shell.tab.garage"].tap()
        XCTAssertTrue(element("garage.screen", in: app).waitForExistence(timeout: 5))
        attach(app, name: "feature-design-garage-configless-en-dark-accessibility")
        app.buttons["shell.tab.social"].tap()
        let social = element("shell.socialHub", in: app)
        XCTAssertTrue(social.waitForExistence(timeout: 5))
        social.buttons["Events"].tap()
        XCTAssertTrue(app.navigationBars["Events"].waitForExistence(timeout: 5))
        XCTAssertTrue(element("events.screen", in: app).exists)
        attach(app, name: "feature-design-events-configless-en-dark-accessibility")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(social.waitForExistence(timeout: 5))
        app.buttons["shell.tab.map"].tap()
        XCTAssertTrue(app.buttons["shell.mapLayers"].isHittable)
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
        XCTAssertGreaterThanOrEqual(privacy.frame.height + 0.001, 48)
        XCTAssertGreaterThanOrEqual(terms.frame.height + 0.001, 48)
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

        let chatFrameBeforeSearch = app.buttons["shell.tab.chat"].frame
        app.buttons["shell.search"].tap()
        let field = app.textFields["addressSearch.query"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        field.typeText("Kungsbacka")
        attach(app, name: "\(prefix)-S02-search-keyboard")
        app.buttons["addressSearch.close"].tap()
        XCTAssertTrue(app.buttons["shell.search"].waitForExistence(timeout: 5))
        XCTAssertFalse(field.exists)
        // The search launcher returns before UIKit finishes dismissing the
        // keyboard. Wait for the real keyboard to leave before targeting the
        // footer at its restored screen position; do not retry a failed tap.
        let keyboardDismissed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch
        )
        XCTAssertEqual(XCTWaiter.wait(for: [keyboardDismissed], timeout: 5), .completed)
        let footerRestored = XCTNSPredicateExpectation(
            predicate: NSPredicate { object, _ in
                guard let button = object as? XCUIElement else { return false }
                return button.isHittable
                    && abs(button.frame.minY - chatFrameBeforeSearch.minY) < 1
                    && abs(button.frame.height - chatFrameBeforeSearch.height) < 1
            }, object: app.buttons["shell.tab.chat"]
        )
        XCTAssertEqual(XCTWaiter.wait(for: [footerRestored], timeout: 5), .completed)

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
            let chats = app.buttons["chatHub.source.chats"]
            chats.tap()
            // The source switch publishes a new SwiftUI accessibility tree.
            // Observe the completed transition before targeting the footer;
            // keep the map selection assertion and never retry either tap.
            assertSelected(chats)
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
        app.buttons["myDrives.stats"].tap()
        XCTAssertTrue(element("myDrives.stats.unavailable", in: app).waitForExistence(timeout: 5))
        let statsClose = app.buttons["myDrives.stats.close"]
        XCTAssertTrue(statsClose.waitForExistence(timeout: 5))
        XCTAssertTrue(statsClose.isHittable)
        statsClose.tap()
        XCTAssertTrue(app.buttons["myDrives.stats"].waitForExistence(timeout: 5))
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
        // Resolve the SwiftUI fixture before the first frame. UIKit's launch
        // default can retain/transition the preceding test's category.
        app.launchArguments += ["-KCCUITestTextSize", largeText ? "AccessibilityXXXL" : "Large"]
        app.launch()
        XCTAssertTrue(
            app.buttons["shell.menu"].waitForExistence(timeout: 15),
            "Build without GoogleService-Info.plist to test the config-less shell"
        )
        XCTAssertEqual(app.buttons["shell.menu"].label, language == "sv" ? "Inställningar" : "Settings")
        // The top menu can be exposed before the safe-area footer finishes its
        // first layout (especially after changing language/text size). Readiness
        // means actual interactive controls, not just an existing title/menu.
        for id in ["map", "chat", "create", "social", "garage"] {
            let button = app.buttons["shell.tab.\(id)"]
            let ready = XCTNSPredicateExpectation(
                predicate: NSPredicate { _, _ in
                    guard button.exists else { return false }
                    let frame = button.frame
                    // XCTest can raise an AX error instead of returning false
                    // for hittable while SwiftUI is still publishing a zero or
                    // offscreen frame. Require valid on-screen geometry first.
                    guard !frame.isEmpty, !frame.isNull, !frame.isInfinite,
                        app.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) else { return false }
                    return button.isHittable
                }, object: button
            )
            XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed,
                "Primary action \(id) was not interactive after launch")
        }
        return app
    }

    private func assertSelected(_ tab: XCUIElement) {
        // A tap returning does not guarantee SwiftUI has published the next
        // accessibility snapshot. Wait for the actual selection, without retaps.
        let selected = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "selected == true"),
            object: tab
        )
        // Hosted runners can publish selection after the initial five seconds;
        // failure diagnostics have then already observed selected=true. Keep the
        // actual-state assertion, with the same bounded wait as launch readiness.
        let result = XCTWaiter.wait(for: [selected], timeout: 15)
        if result != .completed { diagnosePrimaryControls(XCUIApplication()) }
        XCTAssertEqual(result, .completed, "Selection did not settle for \(tab.identifier)")
    }

    private func reveal(_ target: XCUIElement, in scroll: XCUIElement, minimumHeight: CGFloat) {
        // Hittable can mean only a clipped sliver is visible. Inspect the full
        // control before measuring/tapping, in this sheet's actual scroll view.
        for _ in 0..<12 {
            let viewport = scroll.frame
            let frame = target.exists ? target.frame : .zero
            if frame.height >= minimumHeight
                && viewport.insetBy(dx: 0, dy: 1).contains(frame)
                && target.isHittable { return }
            let above = !frame.isEmpty && frame.midY < viewport.midY
            // A press-and-drag can grab a switch/slider instead of scrolling.
            // Use the native scroll gesture, which also negotiates sheet detents.
            if above { scroll.swipeDown(velocity: .slow) }
            else { scroll.swipeUp(velocity: .slow) }
        }
    }

    private func diagnosePrimaryControls(_ app: XCUIApplication) {
        // Fixed control IDs, geometry and booleans only: never user content.
        for id in ["map", "chat", "create", "social", "garage"] {
            let button = app.buttons["shell.tab.\(id)"]
            if button.exists {
                print("Shell diagnostic \(id): selected=\(button.isSelected) hittable=\(button.isHittable) frame=\(button.frame)")
            } else { print("Shell diagnostic \(id): absent") }
        }
        print("Shell diagnostic alertCount=\(app.alerts.count)")
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
