import XCTest

@testable import KCC

/// Unit tests for the map-first shell's pure navigation + toggle logic — the
/// iOS port of Android's `ShellNavTest.kt`, minus the Android-only cases
/// (`rememberSaveable` restore of retired route constants, and the
/// `StubMapSurface` wiring, which has no iOS counterpart yet).
final class ShellNavTests: XCTestCase {

    // MARK: - system Back resolution

    func testBackClosesAnOpenRouteFirst() {
        XCTAssertEqual(
            ShellNavigation.onBack(tab: .social, route: .events),
            .closeRoute
        )
        // Even on the Map tab, an open route closes before anything else.
        XCTAssertEqual(
            ShellNavigation.onBack(tab: .map, route: .profile),
            .closeRoute
        )
    }

    func testBackFromANonMapTabReturnsToTheMapTab() {
        XCTAssertEqual(ShellNavigation.onBack(tab: .history, route: nil), .goToMapTab)
        XCTAssertEqual(ShellNavigation.onBack(tab: .garage, route: nil), .goToMapTab)
    }

    func testBackFromTheMapTabWithNothingOpenExits() {
        XCTAssertEqual(ShellNavigation.onBack(tab: .map, route: nil), .exit)
    }

    // MARK: - route back-stack (hub → child → Back returns to the hub)

    func testOpeningAChildFromAHubStacksTheHubAsItsParent() {
        // Settings is open (no parent); opening Blocked users pushes Settings
        // as its parent, so a later Back knows where to return.
        XCTAssertEqual(
            ShellNavigation.pushRoute(parents: [], current: .settings),
            [.settings]
        )
    }

    func testOpeningATopLevelRouteAddsNoParent() {
        // A root entry (map-home menu / push tap) opens with nothing already
        // open, so it stacks no parent and Back from it returns to the map.
        XCTAssertEqual(ShellNavigation.pushRoute(parents: [], current: nil), [])
    }

    func testBackFromASubMenuReturnsToItsParentHubNotTheMap() {
        // Settings → Blocked users: the parent stack is [settings]. Back must
        // pop to Settings (current), NOT to nil/the map.
        let popped = ShellNavigation.popRoute(parents: [.settings])
        XCTAssertEqual(popped.current, .settings)
        XCTAssertEqual(popped.parents, [])
    }

    func testBackPopsExactlyOneLevelOfADeeperStack() {
        // Friends → member profile → chat: parents are [friends,
        // memberProfile]. Back lands on the member profile, keeping Friends
        // stacked below it.
        let popped = ShellNavigation.popRoute(parents: [.friends, .memberProfile])
        XCTAssertEqual(popped.current, .memberProfile)
        XCTAssertEqual(popped.parents, [.friends])
    }

    func testBackFromATopLevelRouteLeavesNothingOpen() {
        // A root route (empty parent stack) pops to nil, so the shell falls
        // through to its tab Back rules.
        let popped = ShellNavigation.popRoute(parents: [])
        XCTAssertNil(popped.current)
        XCTAssertEqual(popped.parents, [])
    }

    func testSettingsToBlockedRoundTripsPushThenPopBackToSettings() {
        // End-to-end of the flow using only the pure reducer, so the two
        // halves (open, then Back) are asserted to compose correctly.
        let afterSettings = ShellNavigation.pushRoute(parents: [], current: nil)
        let afterBlocked = ShellNavigation.pushRoute(parents: afterSettings, current: .settings)
        XCTAssertEqual(afterBlocked, [.settings])
        // Back from Blocked users pops to Settings, not the map.
        let popped = ShellNavigation.popRoute(parents: afterBlocked)
        XCTAssertEqual(popped.current, .settings)
    }

    func testTheDefaultTabIsMap() {
        // Assert the real default constant (used by production), not case
        // order.
        XCTAssertEqual(ShellTab.defaultTab, .map)
    }

    // MARK: - live-share toggle decision

    func testToggleOpensTheScreenWhenNotWired() {
        XCTAssertEqual(
            LiveShareToggle.action(isSharing: false, canShare: true, wired: false),
            .openScreen
        )
        // Not-wired always opens the screen, even if "sharing" were true.
        XCTAssertEqual(
            LiveShareToggle.action(isSharing: true, canShare: true, wired: false),
            .openScreen
        )
    }

    func testToggleStopsWhenCurrentlySharing() {
        XCTAssertEqual(
            LiveShareToggle.action(isSharing: true, canShare: true, wired: true),
            .stop
        )
    }

    func testToggleStartsWhenWiredNotSharingAndAllowed() {
        XCTAssertEqual(
            LiveShareToggle.action(isSharing: false, canShare: true, wired: true),
            .start
        )
    }

    func testToggleOpensTheScreenWhenStartIsNotPermitted() {
        XCTAssertEqual(
            LiveShareToggle.action(isSharing: false, canShare: false, wired: true),
            .openScreen
        )
    }

    // MARK: - live-share sheet rows

    func testStopSheetWhileSharingOffersStoppingAndNothingElse() {
        let rows = LiveManageSheet.actions(isSharing: true, canShareLive: true, hasStop: true)
        XCTAssertTrue(rows.showStop)
        // Removed from the stop sheet: pressing a stop sign must not open a
        // menu.
        XCTAssertFalse(rows.showHideNow)
        XCTAssertFalse(rows.showAudienceEntry)
        // Not a start surface while already sharing.
        XCTAssertFalse(rows.showStart)
        XCTAssertFalse(rows.showUnavailableNotice)
    }

    func testManageSheetWithoutAStopHandlerStillKeepsHideNowAndAudience() {
        // Turn-by-turn reuses the sheet WITHOUT wiring stop, so no Stop row
        // appears there — but the privacy controls must not vanish.
        let rows = LiveManageSheet.actions(isSharing: true, canShareLive: true, hasStop: false)
        XCTAssertFalse(rows.showStop)
        XCTAssertTrue(rows.showHideNow)
        XCTAssertTrue(rows.showAudienceEntry)
    }

    func testManageSheetWhenIdleAndPermittedOffersStartPlusTheAudienceEntry() {
        let rows = LiveManageSheet.actions(isSharing: false, canShareLive: true, hasStop: false)
        XCTAssertTrue(rows.showStart)
        XCTAssertFalse(rows.showHideNow)
        XCTAssertFalse(rows.showStop)
        XCTAssertFalse(rows.showUnavailableNotice)
        // Who-can-see-me is reachable in every state of the controls sheet.
        XCTAssertTrue(rows.showAudienceEntry)
    }

    func testManageSheetWhenIdleAndFlagOffShowsTheUnavailableNoticeAndAudienceOnly() {
        // canShareLive false = LIVE_LOCATION flag OFF (flag-gated, NOT
        // member-gated). The sheet must explain it's unavailable, never claim
        // a membership is required.
        let rows = LiveManageSheet.actions(isSharing: false, canShareLive: false, hasStop: false)
        XCTAssertFalse(rows.showStart)
        XCTAssertTrue(rows.showUnavailableNotice)
        XCTAssertFalse(rows.showStop)
        XCTAssertFalse(rows.showHideNow)
        XCTAssertTrue(rows.showAudienceEntry)
    }

    // MARK: - map cover: is the map visible, and must it stay live?

    func testMapTabWithNothingOverItIsUncovered() {
        XCTAssertEqual(
            ShellNavigation.mapCover(tab: .map, route: nil, navigating: false, navSearchOpen: false),
            MapCover.none
        )
    }

    func testTranslucentPanelTabsDoNotStandTheMapDown() {
        // History, Social and Garage are translucent panels with a strip of
        // live map above them, so the map behind them is genuinely on screen.
        // Returning .opaque here makes the shell stand the surface down and
        // shows the user a puck-less map through the card.
        for tab in [ShellTab.history, .social, .garage] {
            XCTAssertEqual(
                ShellNavigation.mapCover(tab: tab, route: nil, navigating: false, navSearchOpen: false),
                .transparent,
                "\(tab) is a translucent panel: the map behind it must stay live"
            )
        }
    }

    func testEveryTranslucentPanelTabIsDeclaredAsOne() {
        // Pins the set and its consumer together: adding a tab to
        // translucentPanelTabs without giving it a panel (or the reverse) is
        // the drift this asserts against.
        XCTAssertEqual(translucentPanelTabs, [.history, .social, .garage])
        for tab in translucentPanelTabs {
            XCTAssertEqual(
                ShellNavigation.mapCover(tab: tab, route: nil, navigating: false, navSearchOpen: false),
                .transparent
            )
        }
    }

    func testAFullScreenRouteOverAPanelTabHidesTheMapOutright() {
        // A route opened FROM a panel (Social to Events, say) is a full-screen
        // opaque page: the panel is gone, nothing shows the map. Route beats
        // tab.
        XCTAssertEqual(
            ShellNavigation.mapCover(tab: .social, route: .events, navigating: false, navSearchOpen: false),
            .opaque
        )
    }

    func testTurnByTurnHidesTheMapEvenOverAPanelTab() {
        // Navigation brings its own full-screen map, so the shell's must stand
        // down no matter what is behind it.
        XCTAssertEqual(
            ShellNavigation.mapCover(tab: .garage, route: nil, navigating: true, navSearchOpen: false),
            .opaque
        )
    }

    func testTheAddressSearchKeepsTheMapLive() {
        XCTAssertEqual(
            ShellNavigation.mapCover(tab: .map, route: nil, navigating: false, navSearchOpen: true),
            .transparent
        )
    }

    // MARK: - chat hub gate

    func testChatHubShowsOverTheMapHome() {
        XCTAssertTrue(ShellNavigation.chatHubAllowed(cover: .none, navigating: false))
    }

    func testChatHubShowsOverTurnByTurn() {
        // Navigation carries the map home's chat control, so the control has
        // to be able to open the hub.
        XCTAssertTrue(ShellNavigation.chatHubAllowed(cover: .opaque, navigating: true))
    }

    func testChatHubStaysHiddenOverAFullScreenRouteOrANonMapTab() {
        // Both are .opaque WITHOUT navigating: there is no map behind the
        // popup, which is the whole reason the gate exists.
        XCTAssertFalse(ShellNavigation.chatHubAllowed(cover: .opaque, navigating: false))
    }

    func testChatHubStaysHiddenOverTheAddressSearchAndTheTranslucentPanels() {
        XCTAssertFalse(ShellNavigation.chatHubAllowed(cover: .transparent, navigating: false))
        // Belt and braces: a stale `navigating` flag must not admit the hub
        // over a cover that is not turn-by-turn.
        XCTAssertFalse(ShellNavigation.chatHubAllowed(cover: .transparent, navigating: true))
    }
}
