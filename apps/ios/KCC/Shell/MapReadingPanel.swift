import SwiftUI

/// Reading content belongs to the shell body, above its persistent navigation.
/// Close stays visible when the reading content needs to scroll.
struct MapReadingPanel<Content: View>: View {
    let title: LocalizedStringKey
    let onClose: () -> Void
    var scrollsContent = true
    var leadingImage: String? = nil
    var leadingTint: Color = KccTheme.driver
    var docksToBottom = false
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                if docksToBottom {
                    Capsule().fill(KccTheme.secondary).frame(width: 32, height: 4)
                        .padding(.top, KccSpacing.s2).padding(.bottom, KccSpacing.s1)
                        .accessibilityHidden(true)
                }
            HStack(alignment: .center, spacing: KccSpacing.s2) {
                if let leadingImage {
                    Image(leadingImage).renderingMode(.template).resizable().scaledToFit()
                        .foregroundStyle(leadingTint).frame(width: 40, height: 40)
                        .accessibilityHidden(true)
                }
                Text(title).font(leadingImage == nil ? .headline : .title3.weight(.semibold)).accessibilityAddTraits(.isHeader)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
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
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 16).onEnded { drag in
                if docksToBottom && (drag.translation.height > 72 || drag.predictedEndTranslation.height > 120) { onClose() }
            })
            Divider()
            if scrollsContent {
                ScrollView { readingContent }
                    .accessibilityIdentifier("map.reading.content")
            } else {
                readingContent
            }
        }
        .foregroundStyle(KccTheme.text)
        .tint(KccTheme.driver)
        .background(docksToBottom ? KccTheme.overlay : KccTheme.surface, in: panelShape)
        .overlay { panelShape.strokeBorder(KccTheme.border).allowsHitTesting(false) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("map.reading.panel")
        .accessibilityAction(.escape, onClose)
    }

    private var panelShape: UnevenRoundedRectangle {
        UnevenRoundedRectangle(topLeadingRadius: docksToBottom ? 24 : KccRadius.lg,
            bottomLeadingRadius: docksToBottom ? 0 : KccRadius.lg,
            bottomTrailingRadius: docksToBottom ? 0 : KccRadius.lg,
            topTrailingRadius: docksToBottom ? 24 : KccRadius.lg)
    }

    private var readingContent: some View {
        content()
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(scrollsContent ? KccSpacing.s4 : KccSpacing.s2)
    }
}

enum MapReadingSelection: Hashable {
    case savedPlaces
    case place(PlaceSuggestion)
    case crown(String)
    case incident(String)
    case police(String)
    case camera(String)
    case nearby(String)
    case convoyMember(String)
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
