// Pure (UI-framework-free) shell-host wiring decisions for the single map
// surface, so they are unit-testable without SwiftUI.
//
// Android expresses this inline in `AuthenticatedApp.kt`:
//
//     LaunchedEffect(mapCover, mapSurface) {
//         mapSurface.setActive(mapCover != MapCover.Opaque)
//     }
//
// iOS names the same rule here (a documented deviation: a named pure function
// instead of an inline effect body) so the decision table can be asserted in
// `ShellMapHostTests` against ``ShellNavigation/mapCover(tab:route:navigating:navSearchOpen:)``.

/// The shell-host side of the map-cover rule: what the host DOES with the
/// resolved ``MapCover``.
enum ShellMapHost {
    /// Whether the shell's single map surface stays LIVE under `cover`.
    ///
    /// ``MapCover/none`` and ``MapCover/transparent`` keep it live — the map
    /// is on screen (bare, or behind the address search / a translucent
    /// panel), so it must keep rendering its puck and consuming fixes. Only
    /// ``MapCover/opaque`` stands it down: nothing shows the map, so keeping
    /// it live would only burn battery. The surface itself survives either
    /// way — ``MapSurface/setActive(_:)`` never disposes it.
    static func surfaceActive(cover: MapCover) -> Bool {
        cover != .opaque
    }
}
