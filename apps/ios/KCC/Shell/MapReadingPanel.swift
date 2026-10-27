import SwiftUI

/// Reading content belongs to the shell body, above its persistent navigation.
/// Only the content scrolls; Close remains outside the scroll viewport.
struct MapReadingPanel<Content: View>: View {
    let title: LocalizedStringKey
    let onClose: () -> Void
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: KccSpacing.s2) {
                Text(title).font(.headline).accessibilityAddTraits(.isHeader)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.system(size: 18, weight: .semibold))
                        .frame(width: 48, height: 48).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("shell.panelDismiss"))
                .accessibilityIdentifier("map.reading.close")
            }
            .padding(.horizontal, KccSpacing.s4)
            Divider()
            ScrollView {
                content()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(KccSpacing.s4)
            }
            .accessibilityIdentifier("map.reading.content")
        }
        .foregroundStyle(KccTheme.text)
        .tint(KccTheme.driver)
        .background(KccTheme.surface, in: RoundedRectangle(cornerRadius: KccRadius.lg))
        .overlay { RoundedRectangle(cornerRadius: KccRadius.lg).strokeBorder(KccTheme.border).allowsHitTesting(false) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("map.reading.panel")
        .accessibilityAction(.escape, onClose)
    }
}

enum MapReadingSelection: Hashable {
    case savedPlaces
    case incident(String)
    case police(String)
    case nearby(String)
}

/// Compact map chrome; commands are supplied by the existing shell/coordinator.
/// The active-session caller deliberately does not gate this on canShare.
struct MapLiveSessionControls: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let busy: Bool
    let failed: Bool
    let onManage: () -> Void
    let onStop: (() -> Void)?
    let onHide: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Button(action: onManage) {
                Label("liveLocation.statusSharing", systemImage: "location.fill")
                    .font(.subheadline.weight(.semibold))
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("map.session.manage")
            HStack(spacing: KccSpacing.s2) {
                if let onStop {
                    action("liveLocation.stop", icon: "stop.fill", id: "stop", action: onStop)
                        .tint(KccPalette.errorRed)
                }
                action("liveLocation.hideNow", icon: "eye.slash.fill", id: "hide", action: onHide)
            }
            .disabled(busy)
            if failed {
                ScrollView {
                    Text("liveLocation.error").font(.subheadline).foregroundStyle(KccPalette.errorRed)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(height: 80)
            }
        }
        .padding(KccSpacing.s3)
        .foregroundStyle(KccTheme.text)
        .background(KccTheme.overlay, in: RoundedRectangle(cornerRadius: KccRadius.lg))
        .overlay { RoundedRectangle(cornerRadius: KccRadius.lg).strokeBorder(KccTheme.border).allowsHitTesting(false) }
    }

    private func action(_ title: LocalizedStringKey, icon: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    Image(systemName: icon).font(.system(size: 20, weight: .semibold))
                } else { Label(title, systemImage: icon).font(.subheadline.weight(.semibold)) }
            }
            .frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
        }
        .buttonStyle(.bordered)
        .accessibilityLabel(Text(title))
        .accessibilityIdentifier("map.session.\(id)")
    }
}

struct MapDockHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

struct MapSessionHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
