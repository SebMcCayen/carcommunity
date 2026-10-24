import SwiftUI
import UIKit

/// Resolves the generated semantic roles without copying their source values.
/// UIKit's trait-aware color also resolves correctly in sheets and native bars.
enum KccTheme {
    static let page = adaptive(KccLightColors.pageBackground, KccDarkColors.pageBackground)
    static let surface = adaptive(KccLightColors.surfaceBackground, KccDarkColors.surfaceBackground)
    static let subtle = adaptive(KccLightColors.subtleBackground, KccDarkColors.subtleBackground)
    static let overlay = adaptive(KccLightColors.overlayBackground, KccDarkColors.overlayBackground)
    static let border = adaptive(KccLightColors.overlayBorder, KccDarkColors.overlayBorder)
    static let text = adaptive(KccLightColors.textPrimary, KccDarkColors.textPrimary)
    static let secondary = adaptive(KccLightColors.textSecondary, KccDarkColors.textSecondary)
    static let driver = adaptive(KccLightColors.driverActive, KccDarkColors.driverActive)
    static let onDriver = adaptive(KccLightColors.onDriverActive, KccDarkColors.onDriverActive)
    static let onGold = adaptive(KccLightColors.onBrandPrimary, KccDarkColors.onBrandPrimary)

    private static func adaptive(_ light: Color, _ dark: Color) -> Color {
        Color(uiColor: UIColor { traits in
            UIColor(traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}

extension View {
    func kccMapControl() -> some View {
        foregroundStyle(KccTheme.text)
            .background(KccTheme.overlay, in: Circle())
            .overlay { Circle().strokeBorder(KccTheme.border, lineWidth: 1) }
    }

    func kccReadingSurface() -> some View {
        foregroundStyle(KccTheme.text)
            .background(KccTheme.surface, ignoresSafeAreaEdges: .all)
            .scrollContentBackground(.hidden)
            .toolbarBackground(KccTheme.surface, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
    }
}

/// A translucent shell panel: the bottom-anchored card the History / Social /
/// Garage tabs render OVER the live map (``MapCover/transparent``), leaving a
/// genuinely uncovered strip of visible map above it whose one tap gesture is
/// the outside-tap DISMISS (the map itself stays non-interactive under the
/// strip, exactly as on Android) — the iOS counterpart of Android's
/// `shell/TranslucentPanel.kt`, minimal on purpose.
///
/// Ported now: the height fraction (so the strip of live map above the card is
/// real, which is what makes the ``ShellNavigation/mapCover(tab:route:navigating:navSearchOpen:)``
/// transparent rule honest), the outside-tap dismiss, and the translucent card
/// material. Not ported yet (arrives with the map-UI slice): the drag-down
/// dismiss gesture and its nested-scroll arithmetic.
struct TranslucentShellPanel<Content: View>: View {
    /// Dismissing a panel returns to the Map tab (the tab hubs have no
    /// back-stack of their own) — the caller supplies that tab switch.
    let onDismiss: () -> Void
    @ViewBuilder let content: () -> Content

    /// Fraction of the height the card occupies, anchored to the bottom.
    /// Deliberately below 1 — the remaining strip is live, visible map, the
    /// "outside" that makes the panel read as an overlay rather than a page.
    /// Matches Android's `PANEL_CARD_HEIGHT_FRACTION`.
    private static var cardHeightFraction: CGFloat { 0.92 }

    var body: some View {
        GeometryReader { proxy in
            VStack(spacing: 0) {
                // The outside-tap dismiss target. Hidden from the
                // accessibility tree — an invisible control would only
                // confuse VoiceOver focus order; assistive tech dismisses via
                // the standard escape action on the card below instead
                // (mirroring Android's semantics-based dismiss).
                Button(action: onDismiss) {
                    Color.clear
                        .contentShape(Rectangle())
                }
                .frame(height: proxy.size.height * (1 - Self.cardHeightFraction))
                .accessibilityHidden(true)

                content()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .background(KccTheme.overlay)
                    .clipShape(
                        .rect(
                            topLeadingRadius: KccRadius.lg,
                            topTrailingRadius: KccRadius.lg
                        )
                    )
                    // The standard VoiceOver dismiss (two-finger scrub) —
                    // the accessible counterpart of the outside tap.
                    .accessibilityAction(.escape, onDismiss)
            }
        }
    }
}

#Preview {
    ZStack {
        Color(.secondarySystemBackground).ignoresSafeArea()
        TranslucentShellPanel(onDismiss: {}) {
            Text("shell.socialTitle").padding()
        }
    }
}
