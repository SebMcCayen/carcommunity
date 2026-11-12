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

    #if DEBUG
    func testNewMessageKeepsFriendsLoadedAcrossInboxRedrawsAndReopening() {
        let app = XCUIApplication()
        app.launchArguments = ["-KCCUITestNewDialogue", "-AppleLanguages", "(sv)", "-AppleLocale", "sv_SE"]
        app.launch()
        let compose = app.buttons["conversations.newDialogue"]
        XCTAssertTrue(compose.waitForExistence(timeout: 15))
        for _ in 0..<2 {
            compose.tap()
            let friend = app.buttons["newDialogue.friend.fixture-friend"]
            XCTAssertTrue(friend.waitForExistence(timeout: 5))
            XCTAssertTrue(friend.isHittable)
            XCTAssertEqual(friend.label, "Testvän")
            friend.tap()
            let selected = app.staticTexts["newDialogue.fixture.selected"]
            XCTAssertTrue(selected.waitForExistence(timeout: 5))
            XCTAssertEqual(selected.label, "fixture-friend")
            XCTAssertTrue(compose.waitForExistence(timeout: 5))
        }
        app.terminate()
    }
    #endif

    func testCrownHuntInstructionHeadingsAreLocalized() {
        let translations = [
            (language: "en", intro: "What is Crown Hunt?", collect: "How to collect a crown"),
            (language: "sv", intro: "Vad är Kronjakten?", collect: "Så samlar du en krona")
        ]
        for translation in translations {
            let app = launch(language: translation.language)
            app.buttons["shell.tab.social"].tap()

            let crownHunt = app.buttons["shell.social.crownHunt"]
            XCTAssertTrue(crownHunt.wait(for: \.isHittable, toEqual: true, timeout: 5))
            crownHunt.tap()

            let instructions = app.buttons["crown.instructions.open"]
            XCTAssertTrue(instructions.wait(for: \.isHittable, toEqual: true, timeout: 10))
            instructions.tap()

            XCTAssertTrue(app.staticTexts[translation.intro].waitForExistence(timeout: 5))
            XCTAssertTrue(app.staticTexts[translation.collect].exists)
            app.terminate()
        }
    }

    func testClubsMenuRemainsDiscoverableWithoutBackendActivation() {
        for largeText in [false, true] {
            let app = launch(language: "en", largeText: largeText, theme: "Dark")
            app.buttons["shell.tab.social"].tap()
            let social = element("shell.socialHub", in: app)
            let socialReady = social.waitForExistence(timeout: 5)
            if !socialReady { chatProof(app, name: "social-hub-missing", tree: true) }
            XCTAssertTrue(socialReady)
            let clubs = app.buttons["shell.social.clubs"]
            XCTAssertTrue(clubs.waitForExistence(timeout: 5))
            for _ in 0..<4 {
                if clubs.isHittable { break }
                social.swipeUp()
            }
            XCTAssertTrue(clubs.isHittable)
            XCTAssertGreaterThanOrEqual(clubs.frame.height + 0.001, 48)
            clubs.tap()
            XCTAssertTrue(element("clubs.screen", in: app).waitForExistence(timeout: 5))
            XCTAssertTrue(app.staticTexts["Clubs are unavailable right now."].exists)
            XCTAssertFalse(app.buttons["Create club"].exists)
            XCTAssertFalse(app.buttons["Join"].exists)
            attach(app, name: largeText ? "clubs-unavailable-en-accessibility" : "clubs-unavailable-en")
            app.buttons["Back"].firstMatch.tap()
            XCTAssertTrue(social.waitForExistence(timeout: 5))
            app.buttons["shell.tab.map"].tap()
            XCTAssertTrue(app.buttons["shell.mapLayers"].isHittable)
            app.terminate()
        }
    }

    func testCommunityOpensAtLatestWithAccessibleText() {
        for largeText in [false, true] {
            let app = launch(language: "sv", largeText: largeText, theme: "Dark", chatFixture: true)
            app.buttons["shell.tab.chat"].tap()
            let latest = app.staticTexts["Fixture message 39"]
            let latestExists = latest.waitForExistence(timeout: 15)
            if !latestExists || !latest.isHittable { chatProof(app, name: "latest-message-failure", tree: true) }
            XCTAssertTrue(latestExists)
            XCTAssertTrue(latest.isHittable, "Opening a channel must show its newest message")
            app.terminate()
        }
    }

    func testCommunitySpamSeriesExpandsWithoutHidingTheNewestMessage() {
        for largeText in [false, true] {
            let app = launch(language: "sv", largeText: largeText, theme: "Dark", chatFixture: true, chatFloodFixture: true)
            app.buttons["shell.tab.chat"].tap()
            XCTAssertTrue(app.staticTexts["chat.message.text.fixture-39"].waitForExistence(timeout: 15))
            XCTAssertFalse(app.staticTexts["chat.message.text.fixture-37"].exists)
            let thread = element("chat.thread.messages", in: app)
            // The scroll view's AX frame extends behind the safe-area composer.
            // Keep touch coordinates above that overlay, inside readable rows.
            let emojiRow = app.scrollViews["chat.quickEmojiRow"]
            let footerReady = emojiRow.waitForExistence(timeout: 15)
            if !footerReady { chatProof(app, name: "community-series-footer-missing", tree: true) }
            XCTAssertTrue(footerReady)
            let threadFrame = thread.frame
            let footerTop = emojiRow.frame.minY
            let visibleHeight = min(threadFrame.maxY, footerTop) - threadFrame.minY
            let readableViewport = CGRect(x: threadFrame.minX, y: threadFrame.minY,
                width: threadFrame.width, height: visibleHeight)
            print("CHAT_SERIES_VIEWPORT accessibility=\(largeText) thread=\(threadFrame) footerTop=\(footerTop) visibleHeight=\(visibleHeight)")
            if visibleHeight <= 44 { chatProof(app, name: "community-series-viewport-failure", tree: true) }
            XCTAssertGreaterThan(visibleHeight, 44)
            // XCUI coordinates remain relative to their referenced element.
            // Resolve the readable viewport once, then anchor gestures to the
            // stable application window. Re-resolving the lazy scroll view
            // during each drag can reposition it before the event is injected.
            let appFrame = app.frame
            let origin = app.coordinate(withNormalizedOffset: .zero)
            let lower = origin.withOffset(CGVector(dx: threadFrame.midX - appFrame.minX,
                dy: threadFrame.minY + visibleHeight * 0.8 - appFrame.minY))
            let upper = origin.withOffset(CGVector(dx: threadFrame.midX - appFrame.minX,
                dy: threadFrame.minY + visibleHeight * 0.25 - appFrame.minY))
            let toggle = app.buttons["community-group-toggle-fixture-31"]
            XCTAssertTrue(toggle.waitForExistence(timeout: 15))
            var toggleReady = false
            for _ in 0..<16 {
                if toggle.exists && toggle.isHittable {
                    let frame = toggle.frame
                    if frame.midY > threadFrame.minY + 8 && frame.midY < footerTop - 8 {
                        toggleReady = true
                        break
                    }
                }
                upper.press(forDuration: 0.1, thenDragTo: lower, withVelocity: .slow, thenHoldForDuration: 0.2)
            }
            if !toggleReady { chatProof(app, name: "community-series-toggle-failure", tree: true) }
            XCTAssertTrue(toggleReady)
            print("CHAT_SERIES_TOGGLE accessibility=\(largeText) frame=\(toggle.frame) label=\(toggle.label)")
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            // The toggle can leave the visible AX tree when a tall group opens.
            // Verify the content instead: reach the newest row, read the formerly
            // hidden row above it, then return to the newest. A collapsed group
            // can never satisfy the fixture-37 assertion in either font size.
            for (visit, target) in [("fixture-39", false), ("fixture-37", true), ("fixture-39", false)].enumerated() {
                let (id, older) = target
                let message = app.staticTexts["chat.message.text." + id]
                if message.exists {
                    print("CHAT_SERIES_TARGET accessibility=\(largeText) visit=\(visit) id=\(id) before=\(message.frame)")
                }
                for attempt in 0..<16 {
                    let frame = message.exists ? message.frame : .null
                    let readable = !frame.isNull && !frame.isEmpty && readableViewport.contains(frame)
                    if readable && message.isHittable { break }
                    // Follow the actual row position, including after a pan
                    // passes the middle target. Hittable alone can accept a
                    // row partly hidden behind the safe-area composer.
                    let towardOlder = frame.isNull ? older : frame.minY < readableViewport.minY
                    print("CHAT_SERIES_SCROLL accessibility=\(largeText) visit=\(visit) attempt=\(attempt) frame=\(frame) towardOlder=\(towardOlder)")
                    if towardOlder {
                        upper.press(forDuration: 0.1, thenDragTo: lower, withVelocity: .slow, thenHoldForDuration: 0.2)
                    } else {
                        // fixture-39 is the final row: let a natural fast pan
                        // coast to the newest bound, even with maximum text.
                        // Middle rows still use overlapping, held steps.
                        lower.press(forDuration: 0.1, thenDragTo: upper,
                            withVelocity: id == "fixture-39" ? .fast : .slow,
                            thenHoldForDuration: id == "fixture-39" ? 0 : 0.2)
                    }
                }
                let exists = message.waitForExistence(timeout: 15)
                let finalFrame = exists ? message.frame : .null
                let readable = !finalFrame.isNull && !finalFrame.isEmpty && readableViewport.contains(finalFrame)
                print("CHAT_SERIES_RESULT accessibility=\(largeText) visit=\(visit) id=\(id) frame=\(finalFrame) readable=\(readable)")
                if !exists || !readable || !message.isHittable {
                    chatProof(app, name: "community-series-\(id)-failure", tree: true)
                }
                XCTAssertTrue(exists)
                XCTAssertTrue(readable, "Expanded \(id) must fit inside the readable conversation viewport")
                XCTAssertTrue(message.isHittable,
                    "Expanded \(id) must be readable on visit \(visit), accessibility=\(largeText)")
            }
            attach(app, name: largeText ? "community-series-accessibility" : "community-series")
            app.terminate()
        }
    }

    func testCommunityComposerStaysVisibleWithKeyboard() {
        for largeText in [false, true] {
            let app = launch(language: "sv", largeText: largeText, theme: "Dark", chatFixture: true)
            app.buttons["shell.tab.chat"].tap()
            let composer = app.descendants(matching: .any).matching(identifier: "chat.composer.input").firstMatch
            XCTAssertTrue(composer.waitForExistence(timeout: 15))
            XCTAssertTrue(app.buttons["chat.quickEmoji.happy"].isEnabled,
                "Quick reactions must work with an empty draft")
            // SwiftUI's vertical TextField may expose a measuring TextField
            // as well as the real TextView. Require a tappable editable widget.
            let input = composerInput(in: app)
            XCTAssertTrue(input.waitForExistence(timeout: 5))
            if !input.isHittable {
                chatProof(app, name: "composer-not-hittable", tree: true)
                print("CHAT_COMPOSER_GEOMETRY input=\(input.frame) footer=\(app.buttons["shell.tab.chat"].frame) screen=\(app.frame)")
            }
            XCTAssertTrue(input.isHittable)
            XCTAssertLessThanOrEqual(input.frame.maxY, app.buttons["shell.tab.chat"].frame.minY)
            input.tap()
            XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
            let latest = app.staticTexts["chat.message.text.fixture-39"]
            let dismiss = app.buttons["chat.keyboard.dismiss"]
            XCTAssertTrue(dismiss.waitForExistence(timeout: 5))
            // Resolve the stable ID through the keyboard's AX-tree transition.
            // Keep the poll cheap: querying several frames can exhaust its
            // entire deadline on a loaded hosted simulator. Measure geometry
            // after visibility is established, outside the polling deadline.
            let latestVisible = XCTNSPredicateExpectation(
                predicate: NSPredicate { _, _ in
                    let current = app.staticTexts["chat.message.text.fixture-39"]
                    return current.exists && current.isHittable
                }, object: app)
            let latestReady = XCTWaiter.wait(for: [latestVisible], timeout: 15) == .completed
            if !latestReady { chatProof(app, name: "chat-keyboard-latest-failure", tree: true) }
            XCTAssertTrue(latestReady, "Resizing for the keyboard must keep the latest message visible")
            XCTAssertFalse(app.buttons["shell.tab.chat"].exists, "Hide the primary navigation while writing")
            XCTAssertFalse(app.buttons["chatHub.source.chats"].exists)
            XCTAssertFalse(app.buttons["chatHub.channel.0"].exists)
            XCTAssertFalse(app.buttons["chatHub.events"].exists)
            // Require usable conversation space, not merely a visible composer.
            let titleBottom = app.scrollViews["chat.thread.messages"].frame.minY
            let emojiRow = app.scrollViews["chat.quickEmojiRow"]
            let latestFrame = app.staticTexts["chat.message.text.fixture-39"].frame
            XCTAssertGreaterThanOrEqual(latestFrame.minY, titleBottom)
            XCTAssertLessThanOrEqual(latestFrame.maxY, emojiRow.frame.minY + 1)
            XCTAssertGreaterThan(emojiRow.frame.minY - titleBottom, 200)
            // SwiftUI can replace the editor's native accessibility element.
            // Keep typing into the focused responder through the application.
            app.typeText("Fixture reply")
            assertComposerDraft("Fixture reply", present: true, in: app)
            let send = app.buttons["chat.composer.send"]
            XCTAssertTrue(send.isHittable)
            XCTAssertLessThanOrEqual(send.frame.maxY, app.keyboards.firstMatch.frame.minY + 1)
            XCTAssertLessThanOrEqual(latest.frame.maxY, emojiRow.frame.minY + 1)
            app.buttons["chat.quickEmoji.happy"].tap()
            let happy = app.staticTexts.matching(NSPredicate(
                format: "identifier BEGINSWITH %@ AND label == %@", "chat.message.text.", "😊")).firstMatch
            XCTAssertTrue(happy.waitForExistence(timeout: 5))
            XCTAssertTrue(happy.isHittable)
            assertComposerDraft("Fixture reply", present: true, in: app)
            emojiRow.swipeLeft()
            XCTAssertTrue(app.keyboards.firstMatch.exists, "Browsing quick reactions must keep the keyboard open")
            let crownButton = app.buttons["chat.quickEmoji.crown"]
            XCTAssertTrue(crownButton.isHittable, "The horizontal row must expose Android's final crown reaction")
            crownButton.tap()
            let crown = app.staticTexts.matching(NSPredicate(
                format: "identifier BEGINSWITH %@ AND label == %@", "chat.message.text.", "👑")).firstMatch
            XCTAssertTrue(crown.waitForExistence(timeout: 5))
            XCTAssertTrue(crown.isHittable)
            assertComposerDraft("Fixture reply", present: true, in: app)
            chatProof(app, name: largeText ? "chat-writing-sv-accessibility" : "chat-writing-sv")
            dismiss.tap()
            XCTAssertTrue(app.buttons["shell.tab.chat"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.keyboards.firstMatch.exists)
            assertComposerDraft("Fixture reply", present: true, in: app)
            XCTAssertTrue(app.buttons["chatHub.source.chats"].exists)
            // A vertical editor's AX activation point can land in its padding
            // after accessibility-size layout. Tap the actual editing surface;
            // reopening the keyboard and preserving the draft remain required.
            composerInput(in: app).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
            send.tap()
            let reply = app.staticTexts["Fixture reply"]
            XCTAssertTrue(reply.waitForExistence(timeout: 5))
            XCTAssertTrue(reply.isHittable)
            assertComposerDraft("Fixture reply", present: false, in: app)
            // Keyboard resizing must also respect a deliberate history read.
            // Clearing SwiftUI's vertical editor can already close the keyboard.
            // Dismiss it if needed, then require the same keyboard-free history
            // starting state before exercising the next keyboard transition.
            let historyDismiss = app.buttons["chat.keyboard.dismiss"]
            if historyDismiss.exists { historyDismiss.tap() }
            let keyboardClosed = XCTNSPredicateExpectation(
                predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
            XCTAssertEqual(XCTWaiter.wait(for: [keyboardClosed], timeout: 15), .completed)
            XCTAssertTrue(app.buttons["shell.tab.chat"].waitForExistence(timeout: 5))
            let messages = element("chat.thread.messages", in: app)
            messages.swipeDown()
            messages.swipeDown()
            XCTAssertFalse(reply.exists && reply.isHittable)
            composerInput(in: app).tap()
            XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
            XCTAssertFalse(reply.exists && reply.isHittable, "Opening the keyboard must not jump away from older history")
            app.terminate()
        }
    }

    func testSearchSelectionOffersExplicitNavigationAndKeepsTheMap() {
        for largeText in [false, true] {
            let app = launch(language: "sv", largeText: largeText,
                theme: largeText ? "Dark" : "Light", placeSearchFixture: true)
            let map = app.staticTexts["shell.mapPlaceholder"]
            let identity = map.value as? String
            let mapFrame = map.frame
            app.buttons["shell.search"].tap()
            if largeText {
                let hide = app.buttons["addressSearch.keyboard.dismiss"]
                XCTAssertTrue(hide.waitForExistence(timeout: 5))
                hide.tap()
                let hidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"),
                    object: app.keyboards.firstMatch)
                XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 5), .completed)
            }
            let saved = app.buttons["addressSearch.saved.home"]
            XCTAssertTrue(saved.waitForExistence(timeout: 5))
            saved.tap()
            let name = app.staticTexts["place.navigation.name"]
            XCTAssertTrue(name.waitForExistence(timeout: 5))
            XCTAssertEqual(name.label, "Göteborg Centralstation")
            XCTAssertFalse(app.staticTexts["place.fixture.openedURL"].exists,
                "Selecting a result must not start navigation automatically")
            XCTAssertEqual(map.frame.height, mapFrame.height, accuracy: 1)
            XCTAssertEqual(map.value as? String, identity)
            app.buttons["map.reading.close"].tap()

            app.buttons["shell.search"].tap()
            let query = app.textFields["addressSearch.query"]
            XCTAssertTrue(query.waitForExistence(timeout: 5))
            query.tap()
            query.typeText(largeText ? "station\n" : "station")
            if largeText {
                let hidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"),
                    object: app.keyboards.firstMatch)
                XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 5), .completed)
            }
            let result = app.buttons["addressSearch.result.fixture-varberg"]
            let results = element("addressSearch.results", in: app)
            XCTAssertTrue(results.waitForExistence(timeout: 10))
            // List virtualizes large offscreen rows. Scroll the actual list
            // before requiring the second row's accessibility node to exist.
            for _ in 0..<8 {
                if result.exists && result.isHittable && !result.frame.isInfinite { break }
                results.swipeUp()
            }
            if !result.exists || !result.isHittable { chatProof(app, name: "search-result-unreachable", tree: true) }
            XCTAssertTrue(result.exists)
            XCTAssertTrue(result.isHittable)
            result.tap()
            XCTAssertTrue(name.waitForExistence(timeout: 5))
            XCTAssertEqual(name.label, "Varberg station")
            XCTAssertFalse(query.exists)
            let keyboardGone = XCTNSPredicateExpectation(
                predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
            XCTAssertEqual(XCTWaiter.wait(for: [keyboardGone], timeout: 5), .completed)
            let start = app.buttons["place.navigation.start"]
            reveal(start, in: app.scrollViews["map.reading.content"], minimumHeight: 48)
            XCTAssertTrue(app.buttons["map.reading.close"].isHittable)
            XCTAssertLessThanOrEqual(start.frame.maxY, app.buttons["shell.tab.map"].frame.minY)
            chatProof(app, name: largeText ? "search-navigation-sv-accessibility" : "search-navigation-sv")
            start.tap()
            let guidedDestination = app.staticTexts["navigation.fixture.destination"]
            XCTAssertTrue(guidedDestination.waitForExistence(timeout: 10))
            XCTAssertEqual(guidedDestination.label, "In-app guidance: Varberg station")
            XCTAssertFalse(app.staticTexts["place.fixture.openedURL"].exists,
                "Navigate must never hand off to the phone's Maps app")
            chatProof(app, name: largeText ? "in-app-navigation-sv-accessibility" : "in-app-navigation-sv")
            app.buttons["navigation.fixture.stop"].tap()
            XCTAssertTrue(name.waitForExistence(timeout: 5))
            app.buttons["shell.tab.chat"].tap()
            assertSelected(app.buttons["shell.tab.chat"])
            app.buttons["shell.tab.map"].tap()
            assertSelected(app.buttons["shell.tab.map"])
            XCTAssertFalse(name.exists, "Returning to the map must not reopen the old destination")
            XCTAssertEqual(map.value as? String, identity)
            app.terminate()
        }
    }

    func testInAppRouteFailureCanBeDismissedBackToTheDestination() {
        let app = launch(language: "en", placeSearchFixture: true, rejectMaps: true)
        app.buttons["shell.search"].tap()
        let saved = app.buttons["addressSearch.saved.home"]
        XCTAssertTrue(saved.waitForExistence(timeout: 5))
        saved.tap()
        let start = app.buttons["place.navigation.start"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        start.tap()
        XCTAssertTrue(app.staticTexts["Could not calculate a route. Check your connection and try again."].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Try again"].isHittable)
        XCTAssertFalse(app.staticTexts["place.fixture.openedURL"].exists)
        app.buttons["navigation.cancel"].tap()
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        XCTAssertTrue(start.isHittable)
        XCTAssertEqual(app.staticTexts["place.navigation.name"].label, "Göteborg Centralstation")
        app.buttons["map.reading.close"].tap()
        XCTAssertFalse(start.exists)
        XCTAssertTrue(app.buttons["shell.search"].isHittable)
        app.terminate()
    }

    func testInAppNavigationPreservesTheLiveSessionAndRecordedRoute() {
        let app = launch(language: "en", placeSearchFixture: true, liveFixture: true)
        let bar = app.buttons["map.session.bar"]
        let recordingReady = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let fields = (bar.value as? String ?? "").components(separatedBy: "|")
            return fields.count == 4 && (Int(fields[3]) ?? 0) >= 3
        }, object: bar)
        XCTAssertEqual(XCTWaiter.wait(for: [recordingReady], timeout: 15), .completed)
        let before = (bar.value as? String ?? "").components(separatedBy: "|")
        app.buttons["shell.search"].tap()
        let place = app.buttons["addressSearch.saved.home"]
        XCTAssertTrue(place.waitForExistence(timeout: 5)); place.tap()
        app.buttons["place.navigation.start"].tap()
        let end = app.buttons["navigation.fixture.stop"]
        XCTAssertTrue(end.waitForExistence(timeout: 10))
        XCTAssertFalse(bar.exists && bar.isHittable, "Guidance must cover the footer and its controls")
        chatProof(app, name: "in-app-navigation-keeps-live-recording")
        end.tap()
        XCTAssertTrue(bar.waitForExistence(timeout: 5))
        let after = (bar.value as? String ?? "").components(separatedBy: "|")
        XCTAssertEqual(Array(before.prefix(3)), Array(after.prefix(3)),
            "Guidance must preserve session, start time and recording coordinator identity")
        XCTAssertEqual(after.count, 4)
        if before.count == 4 && after.count == 4 {
            XCTAssertGreaterThanOrEqual(Int(after[3]) ?? 0, Int(before[3]) ?? 0,
                "Opening and closing guidance must not reset the recorded points")
        }
        XCTAssertTrue(app.buttons["shell.tab.create"].isHittable)
        app.terminate()
    }

    func testChatBackLiveMetricsProfileAndStopMatchAndroid() {
        for largeText in [false, true] {
            let app = launch(language: "en", largeText: largeText,
                theme: largeText ? "Dark" : "Light", chatFixture: !largeText, liveFixture: true)
            let map = app.staticTexts["shell.mapPlaceholder"]
            let identity = map.value as? String
            let bar = app.buttons["map.session.bar"]
            XCTAssertTrue(bar.waitForExistence(timeout: 15))
            let speedReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", "36 km/h"), object: bar)
            XCTAssertEqual(XCTWaiter.wait(for: [speedReady], timeout: 10), .completed)
            let stop = app.buttons["shell.tab.create"]
            XCTAssertEqual(stop.label, "Stop sharing")
            XCTAssertEqual(stop.staticTexts.count, 0, "The Stop disc must contain only its icon")
            XCTAssertLessThanOrEqual(bar.frame.maxY, stop.frame.minY)
            XCTAssertFalse(app.staticTexts["Live location active"].exists)
            XCTAssertFalse(app.buttons["map.session.hide"].exists)
            chatProof(app, name: largeText ? "live-session-bar-en-accessibility" : "live-session-bar-en")

            app.buttons["shell.tab.chat"].tap()
            let chats = app.buttons["chatHub.source.chats"]
            let back = app.buttons["chatHub.backToMap"]
            let community = app.buttons["chatHub.channel.0"]
            XCTAssertTrue(chats.waitForExistence(timeout: 10))
            XCTAssertTrue(back.waitForExistence(timeout: 5))
            XCTAssertTrue(back.isHittable)
            XCTAssertLessThanOrEqual(back.frame.maxX, community.frame.minX)
            XCTAssertLessThan(chats.frame.minY, 140, "The source selector belongs at the top without a Chat title")
            XCTAssertFalse(app.navigationBars["Chat"].exists)
            XCTAssertFalse(app.buttons["chatHub.events"].exists)
            chatProof(app, name: largeText ? "chat-header-en-accessibility" : "chat-header-en")

            if !largeText {
                let avatar = app.buttons["chat.sender.profile.fixture-39"]
                XCTAssertTrue(avatar.wait(for: \.isHittable, toEqual: true, timeout: 10))
                avatar.tap()
                XCTAssertTrue(element("memberProfile.screen", in: app).waitForExistence(timeout: 10))
                app.buttons["Back"].firstMatch.tap()
                XCTAssertTrue(avatar.wait(for: \.isHittable, toEqual: true, timeout: 10))
            }

            // Android's fourth chat destination is Club. Notifications is
            // reached from Profile, never a chat channel.
            let club = app.buttons["chatHub.channel.3"]
            let channels = element("chatHub.channels", in: app)
            for _ in 0..<4 {
                let bounds = channels.frame.intersection(app.frame)
                if club.exists, !club.frame.isEmpty, bounds.contains(club.frame) { break }
                channels.swipeLeft()
            }
            XCTAssertTrue(club.isHittable)
            XCTAssertEqual(club.label, "Club")
            club.tap()
            XCTAssertTrue(element("clubChat.screen", in: app).waitForExistence(timeout: 10))
            XCTAssertTrue(app.staticTexts["Join a club in Social → Club to chat. Chat requires a verified connection."].exists)
            XCTAssertFalse(app.buttons["notifications.settings"].exists)
            XCTAssertFalse(app.navigationBars["Chat"].exists)
            chatProof(app, name: largeText ? "chat-club-en-accessibility" : "chat-club-en")
            XCTAssertTrue(back.isHittable)
            back.tap()
            assertSelected(app.buttons["shell.tab.map"])
            XCTAssertEqual(map.value as? String, identity)
            XCTAssertTrue(bar.isHittable, "Back must not stop the session")

            let marker = app.buttons["map.fixture.liveSharer"]
            XCTAssertTrue(marker.wait(for: \.isHittable, toEqual: true, timeout: 10))
            marker.tap()
            let visit = app.buttons["nearby.visitProfile"]
            let visitReady = visit.waitForExistence(timeout: 10)
            if !visitReady { chatProof(app, name: "live-profile-card-missing-after-chat", tree: true) }
            XCTAssertTrue(visitReady, "The nearby marker must open its profile card after returning from Chat")
            reveal(visit, in: app.scrollViews["map.reading.content"], minimumHeight: 48)
            XCTAssertTrue(visit.isHittable)
            XCTAssertLessThanOrEqual(visit.frame.maxY, app.buttons["shell.tab.map"].frame.minY)
            chatProof(app, name: largeText ? "live-profile-card-en-accessibility" : "live-profile-card-en")
            visit.tap()
            XCTAssertTrue(element("memberProfile.screen", in: app).waitForExistence(timeout: 10))
            app.buttons["Back"].firstMatch.tap()
            XCTAssertTrue(bar.waitForExistence(timeout: 10))
            stop.tap()
            let confirmation = app.sheets.firstMatch
            let ready = confirmation.waitForExistence(timeout: 5)
            if !ready { chatProof(app, name: "live-stop-confirmation-missing", tree: true) }
            XCTAssertTrue(ready)
            confirmation.buttons["Stop sharing"].tap()
            let stopped = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: bar)
            XCTAssertEqual(XCTWaiter.wait(for: [stopped], timeout: 10), .completed)
            XCTAssertEqual(stop.label, "Create")
            XCTAssertEqual(stop.staticTexts.count, 0, "The restored plus disc must contain only its icon")
            XCTAssertFalse(app.buttons["shell.postDriveHistory"].exists,
                "Saved drives belong under Profile, not in the map dock after stopping")
            XCTAssertEqual(map.value as? String, identity)
            chatProof(app, name: largeText ? "map-after-stop-en-accessibility" : "map-after-stop-en")

            if !largeText {
                stop.tap()
                let start = app.buttons["shell.createChooser.single"]
                XCTAssertTrue(start.wait(for: \.isHittable, toEqual: true, timeout: 5))
                start.tap()
                XCTAssertTrue(bar.waitForExistence(timeout: 5), "Metrics must appear before the delayed response")
                XCTAssertFalse(bar.isEnabled, "Session management is available after confirmation")
                XCTAssertTrue(bar.wait(for: \.isEnabled, toEqual: true, timeout: 15))
            }
            app.terminate()
        }
    }

    func testSocialAndGarageDragHandlesReturnToTheRetainedMap() {
        for largeText in [false, true] {
            let app = launch(language: "sv", largeText: largeText, theme: largeText ? "Dark" : "Light")
            let identity = app.staticTexts["shell.mapPlaceholder"].value as? String
            XCTAssertNotNil(identity)
            for tab in ["social", "garage"] {
                app.buttons["shell.tab.\(tab)"].tap()
                let handle = element("shell.panel.dragHandle", in: app)
                let handleReady = handle.waitForExistence(timeout: 5)
                if !handleReady { chatProof(app, name: "panel-handle-missing-\(tab)", tree: true) }
                XCTAssertTrue(handleReady)
                XCTAssertTrue(handle.isHittable)
                XCTAssertGreaterThanOrEqual(handle.frame.height + 0.001, 44)
                chatProof(app, name: "panel-\(tab)-\(largeText ? "accessibility" : "regular")")
                let start = handle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.85))
                start.press(forDuration: 0.1, thenDragTo: end)
                let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: handle)
                XCTAssertEqual(XCTWaiter.wait(for: [gone], timeout: 5), .completed)
                assertSelected(app.buttons["shell.tab.map"])
                XCTAssertEqual(app.staticTexts["shell.mapPlaceholder"].value as? String, identity)
                XCTAssertTrue(app.buttons["shell.mapLayers"].isHittable)
            }
            app.terminate()
        }
    }

    func testCrownTilePaletteFollowsMapAppearanceOverride() {
        for theme in ["Light", "Dark"] {
            let app = launch(language: "sv", theme: theme, markerPoints: [CGPoint(x: -100, y: -100)])
            for (choice, expected) in [("day", "Day"), ("night", "Night")] {
                app.buttons["shell.mapLayers"].tap()
                let button = app.buttons["shell.layersAppearance.\(choice)"]
                reveal(button, in: app.scrollViews["shell.layers.content"], minimumHeight: 48)
                button.tap()
                assertSelected(button)
                dismissSheet(app, title: "Kartlager")
                let mode = app.staticTexts["map.fixture.crown-mode"]
                let changed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", expected), object: mode)
                XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 5), .completed)
                chatProof(app, name: "map-crowns-app-\(theme.lowercased())-map-\(choice)")
            }
            // Do not leak a manual map-mode override to subsequent smoke tests.
            app.buttons["shell.mapLayers"].tap()
            let system = app.buttons["shell.layersAppearance.system"]
            reveal(system, in: app.scrollViews["shell.layers.content"], minimumHeight: 48)
            system.tap()
            assertSelected(system)
            dismissSheet(app, title: "Kartlager")
            app.terminate()
        }
    }

    func testTrafficMarkersStayBelowMapControlsInBothThemes() {
        for theme in ["Light", "Dark"] {
            let baseline = launch(language: "sv", theme: theme)
            let controlIDs = ["shell.menu", "shell.mapLayers"]
            let points = controlIDs.map { id in
                let frame = baseline.buttons[id].frame
                return CGPoint(x: frame.midX, y: frame.midY)
            }
            baseline.terminate()
            let app = launch(language: "sv", theme: theme, markerPoints: points)
            XCTAssertTrue(app.buttons["map.fixture.covered.0"].exists)
            XCTAssertTrue(app.buttons["map.fixture.covered.1"].exists)
            chatProof(app, name: "map-controls-markers-\(theme.lowercased())")
            // These exact centres also contain tappable incident markers.
            // The controls must draw and receive touches above those markers.
            app.buttons["shell.menu"].tap()
            let settings = app.buttons["shell.settings"]
            XCTAssertTrue(settings.waitForExistence(timeout: 5))
            XCTAssertFalse(app.staticTexts["map.fixture.wrongTarget"].exists)
            settings.tap()
            XCTAssertTrue(app.navigationBars["Inställningar"].waitForExistence(timeout: 5))
            app.buttons["Tillbaka"].firstMatch.tap()
            app.buttons["shell.mapLayers"].tap()
            XCTAssertTrue(app.navigationBars["Kartlager"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.staticTexts["map.fixture.wrongTarget"].exists)
            app.terminate()
        }
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
        for mode in ["CourseUp", "NorthUp"] {
            compass.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            let updated = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                compass.exists && (compass.value as? String) == mode
            }, object: nil)
            let result = XCTWaiter.wait(for: [updated], timeout: 15)
            if result != .completed {
                chatProof(app, name: "compass-\(mode)-center-tap-failure", tree: true)
            }
            XCTAssertEqual(result, .completed, "One tap at the center of Compass must switch to \(mode)")
        }
        layers.tap()
        XCTAssertTrue(app.navigationBars["Map layers"].waitForExistence(timeout: 5))
    }

    func testSavedPlacesDockKeepsCloseToolsAndPrimaryNavigationReachable() {
        let app = launch(language: "en", largeText: true, theme: "Dark")
        let mapIdentity = app.staticTexts["shell.mapPlaceholder"].value as? String
        let mapFrame = app.staticTexts["shell.mapPlaceholder"].frame
        XCTAssertNotNil(mapIdentity)
        let saved = app.buttons["shell.savedPlaces"]
        XCTAssertFalse(saved.exists, "Saved places belongs in the avatar menu")
        app.buttons["shell.menu"].tap()
        XCTAssertTrue(saved.waitForExistence(timeout: 15))
        saved.tap()
        let panel = element("map.reading.panel", in: app)
        let close = app.buttons["map.reading.close"]
        XCTAssertTrue(panel.waitForExistence(timeout: 5))
        let readingMapFrame = app.staticTexts["shell.mapPlaceholder"].frame
        XCTAssertEqual(readingMapFrame.minY, mapFrame.minY, accuracy: 1,
            "Reading a marker must keep the map's full viewport behind its panel")
        XCTAssertEqual(readingMapFrame.height, mapFrame.height, accuracy: 1)
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
        app.buttons["shell.menu"].tap()
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
        dismissSheet(app, title: "Map layers")
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
        let screenshot = XCTAttachment(screenshot: captureScreen(app))
        screenshot.name = "map-layers-sv-dark-accessibility"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        XCTAssertFalse(app.buttons["Stäng kartlager"].exists)
        dismissSheet(app, title: "Kartlager")
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

    func testMyDrivesTitleSharesNavigationBarWithBackAndStatistics() {
        let app = launch(language: "en")
        app.buttons["shell.menu"].tap()
        let myDrives = app.buttons["shell.myDrives"]
        XCTAssertTrue(myDrives.waitForExistence(timeout: 5))
        myDrives.tap()

        let navigationBar = app.navigationBars["My drives"]
        XCTAssertTrue(navigationBar.waitForExistence(timeout: 5))
        let back = navigationBar.buttons["Back"]
        XCTAssertTrue(back.wait(for: \.isHittable, toEqual: true, timeout: 5))
        let statistics = navigationBar.buttons["myDrives.stats"]
        XCTAssertTrue(statistics.wait(for: \.isHittable, toEqual: true, timeout: 5))
        back.tap()
        XCTAssertTrue(app.buttons["profile.myDrives"].waitForExistence(timeout: 5))
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
            // The center can fall between the icon and caption at this size.
            tab.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            if id == "social" {
                guard element("shell.socialHub", in: app).waitForExistence(timeout: 15) else {
                    chatProof(app, name: "social-center-tap-failure", tree: true)
                    XCTFail("One tap at the center of Social must open its panel")
                    return
                }
            } else if id == "chat" {
                // The first cold chat render can outlive XCUITest's idle wait.
                // Wait for the actual destination, without retrying the tap.
                XCTAssertTrue(element("chatHub.opaqueScreen", in: app).waitForExistence(timeout: 15))
            }
            assertSelected(tab)
        }
        for id in ["driver", "chats"] {
            let source = app.buttons["chatHub.source.\(id)"]
            XCTAssertTrue(source.wait(for: \.isHittable, toEqual: true, timeout: 15))
            XCTAssertGreaterThanOrEqual(source.frame.height, 44)
            XCTAssertLessThanOrEqual(source.frame.height, 80)
            // Exercise the same physical center tap as the footer and channel
            // controls, after the newly opened chat surface becomes hittable.
            source.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
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
        // Club is intentionally reached by horizontal scrolling, not
        // by removing or shrinking away one of the existing channel routes.
        for index in 0..<4 {
            let channel = app.buttons["chatHub.channel.\(index)"]
            for _ in 0..<4 {
                if channel.isHittable && channels.frame.contains(channel.frame) { break }
                channels.swipeLeft()
            }
            XCTAssertTrue(channel.isHittable)
            XCTAssertTrue(channels.frame.contains(channel.frame))
            channel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            assertSelected(channel)
        }
        attach(app, name: "configless-en-Dark-AXXXL-S29-club-reachable")
        app.buttons["shell.tab.create"].tap()
        assertSelected(app.buttons["shell.tab.map"])
    }

    private func checkShell(language: String, labels: [String]) {
        let app = launch(language: language)
        let ids = ["map", "chat", "social", "garage"]
        assertSelected(app.buttons["shell.tab.map"])
        let create = app.buttons["shell.tab.create"]
        XCTAssertEqual(create.label, language == "sv" ? "Skapa" : "Create")
        XCTAssertEqual(create.staticTexts.count, 0, "Create must keep an accessible name without visible text")
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
        let search = app.buttons["shell.search"]
        XCTAssertTrue(search.isHittable)
        let layers = app.buttons["shell.mapLayers"]
        let avatar = app.buttons["shell.menu"]
        XCTAssertTrue(layers.isHittable)
        XCTAssertGreaterThanOrEqual(layers.frame.minY, avatar.frame.maxY)
        XCTAssertLessThanOrEqual(layers.frame.minY, avatar.frame.maxY + 32,
            "S01 rail must start just below top chrome, not at map center")
        attach(app, name: "\(prefix)-S01-map-collapsed-search")

        let chatFrameBeforeSearch = app.buttons["shell.tab.chat"].frame
        search.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let field = app.textFields["addressSearch.query"]
        guard field.waitForExistence(timeout: 5) else {
            chatProof(app, name: "\(prefix)-search-open-failure", tree: true)
            XCTFail("One tap at the center of Search must open the address field")
            return
        }
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        field.typeText("Kungsbacka")
        attach(app, name: "\(prefix)-S02-search-keyboard")
        app.buttons["addressSearch.close"].tap()
        XCTAssertTrue(search.waitForExistence(timeout: 5))
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
            XCTAssertTrue(app.buttons["chatHub.source.chats"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.navigationBars[language == "sv" ? "Chatt" : "Chat"].exists)
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
        XCTAssertFalse(app.buttons["myDrives.stats.close"].exists)
        dismissSheet(app, title: nil)
        XCTAssertTrue(app.buttons["myDrives.stats"].waitForExistence(timeout: 5))
        app.buttons[language == "sv" ? "Tillbaka" : "Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["profile.myDrives"].waitForExistence(timeout: 5))
        app.buttons[language == "sv" ? "Tillbaka" : "Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["shell.menu"].waitForExistence(timeout: 5))
        XCTAssertEqual(map.value as? String, mapIdentity)
        openSettings(app, language: language)
        attach(app, name: "\(prefix)-S69-settings")
    }

    /// Only config-less synthetic fixtures call this. Keep the actual screenshot
    /// in XCTest attachments; duplicate image payloads truncate live CI logs.
    private func chatProof(_ app: XCUIApplication, name: String, tree: Bool = false) {
        if tree { print("CHAT_FIXTURE_TREE \(app.debugDescription)") }
        let screenshot = captureScreen(app)
        attachScreenshot(screenshot, name: name)
        print("KCC_CHAT_PROOF_ATTACHMENT \(name)")
    }

    /// SwiftUI's vertical field can replace its measuring UITextField with a
    /// UITextView after input/layout changes. Re-query the visible editor;
    /// retaining the original element can read a retired measuring widget.
    private func visibleComposerInput(in app: XCUIApplication) -> XCUIElement? {
        for fields in [app.textViews, app.textFields] {
            let candidates = fields.matching(identifier: "chat.composer.input").allElementsBoundByIndex
            if let editor = candidates.first(where: { $0.isHittable }) { return editor }
        }
        return nil
    }

    private func composerInput(in app: XCUIApplication) -> XCUIElement {
        visibleComposerInput(in: app)
            ?? app.descendants(matching: .any).matching(identifier: "chat.composer.input").firstMatch
    }

    private func assertComposerDraft(_ text: String, present: Bool, in app: XCUIApplication,
                                     file: StaticString = #filePath, line: UInt = #line) {
        // Read the live editor value so a replaced widget or a slow XCTest
        // element description cannot hold up the draft check.
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            guard let editor = self.visibleComposerInput(in: app) else { return false }
            let value = editor.value as? String ?? ""
            return value.contains(text) == present
        }, object: nil)
        let result = XCTWaiter.wait(for: [expectation], timeout: 30)
        if result != .completed { chatProof(app, name: "chat-draft-value-failure", tree: true) }
        let message = present ? "Visible editor must preserve the draft until Send" : "Send must clear the visible draft"
        XCTAssertEqual(result, .completed, message, file: file, line: line)
    }

    private func attach(_ app: XCUIApplication, name: String) {
        // Reached only after launch verifies the config-less Settings identity.
        // Every capture is an actual simulator render; no production fixtures.
        attachScreenshot(captureScreen(app), name: name)
    }

    private func captureScreen(_ app: XCUIApplication) -> XCUIScreenshot {
        XCTAssertEqual(app.state, .runningForeground, "Capture the config-less app in the foreground")
        return XCUIScreen.main.screenshot()
    }

    private func attachScreenshot(_ screenshot: XCUIScreenshot, name: String) {
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func dismissSheet(_ app: XCUIApplication, title: String?) {
        let bar = title.map { app.navigationBars[$0] }
            ?? app.navigationBars.allElementsBoundByIndex.first(where: { $0.isHittable })
            ?? app.navigationBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 5))
        let start = bar.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.95))
        start.press(forDuration: 0.1, thenDragTo: end)
    }

    private func launch(language: String, largeText: Bool = false, theme: String = "Light", chatFixture: Bool = false, chatFloodFixture: Bool = false, markerPoints: [CGPoint] = [], placeSearchFixture: Bool = false, rejectMaps: Bool = false, liveFixture: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(\(language))", "-AppleLocale", language]
        app.launchArguments += ["-KCCUITestTheme", theme]
        // Resolve the SwiftUI fixture before the first frame. UIKit's launch
        // default can retain/transition the preceding test's category.
        app.launchArguments += ["-KCCUITestTextSize", largeText ? "AccessibilityXXXL" : "Large"]
        if chatFixture { app.launchArguments += ["-KCCUITestChatThread"] }
        if chatFloodFixture { app.launchArguments += ["-KCCUITestChatFlood"] }
        if placeSearchFixture { app.launchArguments += ["-KCCUITestPlaceSearch"] }
        if rejectMaps { app.launchArguments += ["-KCCUITestNavigationFailure"] }
        if liveFixture { app.launchArguments += ["-KCCUITestLiveSession"] }
        if !markerPoints.isEmpty {
            app.launchArguments += ["-KCCUITestMapMarkers", markerPoints.map { "\($0.x),\($0.y)" }.joined(separator: ";")]
        }
        app.launch()
        XCTAssertTrue(
            app.buttons["shell.menu"].waitForExistence(timeout: 15),
            "Build without GoogleService-Info.plist to test the config-less shell"
        )
        XCTAssertEqual(app.buttons["shell.menu"].label, language == "sv" ? "Inställningar" : "Settings")
        if liveFixture {
            // The fixture publishes its active session asynchronously. Let the
            // session dock settle before querying footer activation points;
            // otherwise its first insertion can retire the sampled AX frame.
            let bar = app.buttons["map.session.bar"]
            let dockReady = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                guard bar.exists else { return false }
                let frame = bar.frame
                return !frame.isEmpty && !frame.isNull && !frame.isInfinite && app.frame.intersects(frame)
            }, object: bar)
            XCTAssertEqual(XCTWaiter.wait(for: [dockReady], timeout: 15), .completed,
                "The active-session dock must be laid out before footer interaction")
        }
        // Existence can precede the first usable accessibility snapshot. On a
        // cold hosted simulator that snapshot can itself take over 15 seconds;
        // wait for interactive controls before checking their exact geometry.
        // Re-read the window as well: its first launch frame can still change.
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 15))
        for id in ["map", "chat", "create", "social", "garage"] {
            let button = app.buttons["shell.tab.\(id)"]
            XCTAssertTrue(button.wait(for: \.isHittable, toEqual: true, timeout: 30),
                "Primary action \(id) must become interactive after launch")
            let layout = XCTNSPredicateExpectation(
                predicate: NSPredicate { _, _ in
                    let frame = button.frame
                    let windowFrame = window.frame
                    return frame.width >= 44 && frame.height >= 44 && windowFrame.contains(frame)
                },
                object: nil
            )
            let layoutResult = XCTWaiter.wait(for: [layout], timeout: 30)
            if layoutResult != .completed { chatProof(app, name: "primary-\(id)-layout-failure", tree: true) }
            XCTAssertEqual(layoutResult, .completed, "Primary action \(id) must be laid out after launch")
        }
        return app
    }

    private func assertSelected(_ tab: XCUIElement) {
        // A tap returning does not guarantee SwiftUI has published the next
        // accessibility snapshot. Wait for the actual selection, without retaps.
        let selected = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in tab.exists && tab.isSelected },
            object: nil
        )
        // Hosted runners can publish selection after the initial five seconds;
        // failure diagnostics have then already observed selected=true. Keep the
        // actual-state assertion, with the same bounded wait as launch readiness.
        let result = XCTWaiter.wait(for: [selected], timeout: 15)
        if result != .completed {
            let app = XCUIApplication()
            chatProof(app, name: "\(tab.identifier)-selection-failure", tree: true)
            diagnosePrimaryControls(app)
        }
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
