import SwiftUI

/// One bounded, keyboard-aware viewport for every message thread. Start at
/// the latest message, follow arrivals while near the bottom, and preserve
/// the reader's position when they scroll back or prepend an older page.
struct ChatThreadScrollView<Content: View>: View {
    let latestMessageID: String?
    let latestIsOwn: Bool
    @ViewBuilder let content: () -> Content
    @Environment(\.chatReadingActive) private var readingActive
    @Namespace private var coordinateSpace
    @State private var followsLatest = true
    private let endID = "chat.thread.end"

    var body: some View {
        GeometryReader { viewport in
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: KccSpacing.s2) {
                        content()
                        Color.clear.frame(height: 1)
                            .id(endID)
                            .background {
                                GeometryReader { end in
                                    Color.clear.preference(key: ChatThreadBottomKey.self,
                                        value: end.frame(in: .named(coordinateSpace)).maxY)
                                }
                            }
                    }
                    .padding(KccSpacing.s3)
                }
                .coordinateSpace(name: coordinateSpace)
                .defaultScrollAnchor(.bottom)
                .scrollDismissesKeyboard(.interactively)
                .onPreferenceChange(ChatThreadBottomKey.self) { bottom in
                    guard let bottom else { followsLatest = false; return }
                    let visibleBottom = max(0, viewport.size.height - viewport.safeAreaInsets.bottom)
                    followsLatest = bottom >= 0 && bottom <= visibleBottom + 64
                }
                .onChange(of: latestMessageID, initial: true) { old, newest in
                    guard let newest, readingActive,
                          old == newest || old == nil || followsLatest || latestIsOwn else { return }
                    // No animation across long histories on initial load.
                    // Target a real row in the SAME lazy stack as the end
                    // marker. An external sentinel can scroll past the rows
                    // before SwiftUI has materialized them with bottom insets.
                    proxy.scrollTo(newest, anchor: .bottom)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct ChatThreadBottomKey: PreferenceKey {
    static let defaultValue: CGFloat? = nil
    static func reduce(value: inout CGFloat?, nextValue: () -> CGFloat?) {
        value = nextValue() ?? value
    }
}
