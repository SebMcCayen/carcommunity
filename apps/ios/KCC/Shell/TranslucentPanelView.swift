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
    // Use the platform bright error red on dark surfaces; retain the canonical
    // darker red against light cards. No new brand/palette value is introduced.
    static let error = adaptive(KccPalette.errorRed, Color(uiColor: .systemRed))
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
    func kccPrimaryAction() -> some View {
        modifier(KccPrimaryActionAppearance())
    }

    /// Reading card shared by the feature pages; no material blur over tiles.
    func kccContentCard(border: Color = KccTheme.border, lineWidth: CGFloat = 1) -> some View {
        padding(KccSpacing.s4)
            .foregroundStyle(KccTheme.text)
            .background(KccTheme.surface, in: RoundedRectangle(cornerRadius: KccRadius.lg))
            .overlay {
                RoundedRectangle(cornerRadius: KccRadius.lg)
                    .strokeBorder(border, lineWidth: lineWidth)
                    .allowsHitTesting(false)
            }
    }

    func kccMapControl(isActive: Bool = false) -> some View {
        modifier(KccMapControlAppearance(isActive: isActive))
    }

    func kccReadingSurface(pageBackground: Bool = false) -> some View {
        foregroundStyle(KccTheme.text)
            .background(pageBackground ? KccTheme.page : KccTheme.surface, ignoresSafeAreaEdges: .all)
            .scrollContentBackground(.hidden)
            .buttonBorderShape(.roundedRectangle(radius: KccRadius.md))
            .toolbarBackground(pageBackground ? KccTheme.page : KccTheme.surface, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
    }
}

/// Android CircleControl: opaque semantic surface, onSurface glyph, 1pt
/// border and 3pt elevation. Resolve against the SwiftUI app appearance,
/// including explicit app theme overrides, rather than a UIKit trait cache.
private struct KccMapControlAppearance: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    let isActive: Bool

    func body(content: Content) -> some View {
        let dark = scheme == .dark
        let surface = isActive
            ? (dark ? KccDarkColors.subtleBackground : KccPalette.softSand)
            : (dark ? KccDarkColors.overlayBackground : KccLightColors.overlayBackground)
        content
            .foregroundStyle(dark ? KccDarkColors.textPrimary : KccLightColors.textPrimary)
            .background(surface, in: Circle())
            .overlay {
                Circle().strokeBorder(dark ? KccDarkColors.overlayBorder : KccLightColors.overlayBorder, lineWidth: 1)
                    .allowsHitTesting(false)
            }
            .shadow(color: .black.opacity(dark ? 0.3 : 0.18), radius: 3, y: 2)
    }
}

private struct KccPrimaryActionAppearance: ViewModifier {
    @Environment(\.isEnabled) private var enabled
    func body(content: Content) -> some View {
        content.buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: KccRadius.md))
            .tint(KccPalette.crownGold)
            .foregroundStyle(enabled ? KccTheme.onGold : KccTheme.secondary)
    }
}

enum ShellPanelDragPolicy {
    static func shouldDismiss(offset: CGFloat, predictedOffset: CGFloat, panelHeight: CGFloat) -> Bool {
        guard panelHeight > 0, offset > 0 else { return false }
        let momentum = predictedOffset - offset
        if momentum < -100 { return false }
        return momentum > 100 || offset >= panelHeight * 0.35
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
/// material and a dedicated drag handle. Scrolling the content remains a
/// separate gesture, so long lists cannot accidentally dismiss their panel.
struct TranslucentShellPanel<Content: View>: View {
    /// Dismissing a panel returns to the Map tab (the tab hubs have no
    /// back-stack of their own) — the caller supplies that tab switch.
    let onDismiss: () -> Void
    @ViewBuilder let content: () -> Content
    @State private var dragOffset: CGFloat = 0

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

                VStack(spacing: 0) {
                    dragHandle(panelHeight: proxy.size.height * Self.cardHeightFraction)
                    content()
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .background(KccTheme.overlay)
                    .clipShape(
                        .rect(
                            topLeadingRadius: KccRadius.lg,
                            topTrailingRadius: KccRadius.lg
                        )
                    )
                    .offset(y: dragOffset)
                    .accessibilityIdentifier("shell.panel")
                    // The standard VoiceOver dismiss (two-finger scrub) —
                    // the accessible counterpart of the outside tap.
                    .accessibilityAction(.escape, onDismiss)
            }
        }
    }

    private func dragHandle(panelHeight: CGFloat) -> some View {
        Capsule().fill(KccTheme.secondary.opacity(0.55))
            .frame(width: 32, height: 4)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 10, coordinateSpace: .global)
                .onChanged { dragOffset = max(0, $0.translation.height) }
                .onEnded { value in
                    if ShellPanelDragPolicy.shouldDismiss(
                        offset: value.translation.height,
                        predictedOffset: value.predictedEndTranslation.height,
                        panelHeight: panelHeight
                    ) {
                        onDismiss()
                    } else {
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { dragOffset = 0 }
                    }
                })
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("shell.panelDragHandle"))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction(onDismiss)
            .accessibilityIdentifier("shell.panel.dragHandle")
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
