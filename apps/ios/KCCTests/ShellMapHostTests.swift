import XCTest

@testable import KCC

/// Unit tests for the shell-host surface-liveness rule — the named iOS
/// counterpart of Android's inline
/// `mapSurface.setActive(mapCover != MapCover.Opaque)` effect. The second
/// group pins the WIRING decision table: composed with
/// `ShellNavigation.mapCover`, `setActive` must land exactly where the cover
/// rule says the map is (in)visible.
final class ShellMapHostTests: XCTestCase {

    // MARK: - the rule itself, per cover

    func testAnUncoveredMapStaysLive() {
        XCTAssertTrue(ShellMapHost.surfaceActive(cover: .none))
    }

    func testATransparentCoverKeepsTheSurfaceLive() {
        // The map behind the address search / a translucent panel is
        // genuinely on screen: standing it down would show a puck-less map
        // through the card.
        XCTAssertTrue(ShellMapHost.surfaceActive(cover: .transparent))
    }

    func testAnOpaqueCoverStandsTheSurfaceDown() {
        XCTAssertFalse(ShellMapHost.surfaceActive(cover: .opaque))
    }

    // MARK: - decision table, composed with mapCover (the actual wiring)

    private func active(
        tab: ShellTab,
        route: ShellRoute?,
        navigating: Bool = false,
        navSearchOpen: Bool = false
    ) -> Bool {
        ShellMapHost.surfaceActive(
            cover: ShellNavigation.mapCover(
                tab: tab,
                route: route,
                navigating: navigating,
                navSearchOpen: navSearchOpen
            )
        )
    }

    func testTheMapHomeKeepsTheSurfaceLive() {
        XCTAssertTrue(active(tab: .map, route: nil))
    }

    func testEveryTranslucentPanelTabKeepsTheSurfaceLive() {
        // History / Social / Garage draw a strip of live map above their
        // card, so the surface must not stand down under them.
        for tab in translucentPanelTabs {
            XCTAssertTrue(
                active(tab: tab, route: nil),
                "\(tab) is a translucent panel: the surface must stay live"
            )
        }
    }

    func testTheCreateTabStandsTheSurfaceDown() {
        // Create is an opaque page, not a panel — nothing shows the map.
        XCTAssertFalse(active(tab: .create, route: nil))
    }

    func testAnOpenRouteStandsTheSurfaceDownOnEveryTab() {
        // A full-screen route hides the map outright, even when opened from
        // a panel tab (Social → Events) or over the map home itself.
        XCTAssertFalse(active(tab: .social, route: .events))
        XCTAssertFalse(active(tab: .map, route: .profile))
        XCTAssertFalse(active(tab: .garage, route: .settings))
    }

    func testTurnByTurnStandsTheSurfaceDown() {
        // Navigation brings its own map; the shell's is hidden outright.
        XCTAssertFalse(active(tab: .map, route: nil, navigating: true))
    }

    func testTheAddressSearchKeepsTheSurfaceLive() {
        // Search chrome draws over a map the user is still looking at.
        XCTAssertTrue(active(tab: .map, route: nil, navSearchOpen: true))
    }

    func testTheTableMatchesMapCoverForEveryTab() {
        // Drift guard: whatever cover a tab resolves to, the wiring stands
        // the surface down exactly on .opaque — never on the other covers.
        for tab in ShellTab.allCases {
            for route in [nil, ShellRoute.events, .profile] {
                let cover = ShellNavigation.mapCover(
                    tab: tab, route: route, navigating: false, navSearchOpen: false
                )
                XCTAssertEqual(
                    ShellMapHost.surfaceActive(cover: cover),
                    cover != .opaque,
                    "\(tab)/\(String(describing: route)) diverged from mapCover"
                )
            }
        }
    }
}
