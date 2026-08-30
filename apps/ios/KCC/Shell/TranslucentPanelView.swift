import SwiftUI

/// A translucent shell panel: the bottom-anchored card the History / Social /
/// Garage tabs render OVER the live map (``MapCover/transparent``), leaving a
/// genuinely uncovered, tappable strip of map above it — the iOS counterpart
/// of Android's `shell/TranslucentPanel.kt`, minimal on purpose.
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
                Button(action: onDismiss) {
                    Color.clear
                        .contentShape(Rectangle())
                }
                .frame(height: proxy.size.height * (1 - Self.cardHeightFraction))
                .accessibilityLabel(Text("shell.panelDismiss"))

                content()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .background(.regularMaterial)
                    .clipShape(
                        .rect(
                            topLeadingRadius: KccRadius.lg,
                            topTrailingRadius: KccRadius.lg
                        )
                    )
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
