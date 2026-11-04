import SwiftUI
import UIKit

/// One bounded, keyboard-aware viewport for every message thread. Start at
/// the latest message, follow arrivals while near the bottom, and preserve
/// the reader's position when they scroll back or prepend an older page.
struct ChatThreadScrollView<Content: View>: View {
    let latestMessageID: String?
    let latestIsOwn: Bool
    var latestScrollTargetID: String? = nil
    @ViewBuilder let content: () -> Content
    @Environment(\.chatReadingActive) private var readingActive
    @Namespace private var coordinateSpace
    @State private var followsLatest = true
    @State private var readerHasScrolled = false
    @State private var pinnedBeforeKeyboardResize: Bool?
    @GestureState private var dragging = false
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
                .accessibilityIdentifier("chat.thread.messages")
                .coordinateSpace(name: coordinateSpace)
                .defaultScrollAnchor(.bottom)
                .scrollDismissesKeyboard(.interactively)
                .simultaneousGesture(DragGesture()
                    .updating($dragging) { _, state, _ in state = true }
                    .onChanged { _ in readerHasScrolled = true })
                .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { _ in
                    pinnedBeforeKeyboardResize = (latestMessageID == nil || followsLatest) && !dragging
                }
                .onChange(of: viewport.size) { _, _ in
                    if readingActive, !dragging, pinnedBeforeKeyboardResize ?? followsLatest,
                       let newest = latestMessageID {
                        proxy.scrollTo(latestScrollTargetID ?? newest, anchor: .bottom)
                    }
                }
                .onChange(of: viewport.safeAreaInsets.bottom) { _, _ in
                    // A composer inset can settle after the keyboard frame.
                    // Keep the same bottom owner through both layout changes.
                    if readingActive, !dragging, pinnedBeforeKeyboardResize ?? followsLatest,
                       let newest = latestMessageID {
                        proxy.scrollTo(latestScrollTargetID ?? newest, anchor: .bottom)
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidChangeFrameNotification)) { _ in
                    if readingActive, !dragging, pinnedBeforeKeyboardResize == true,
                       let newest = latestMessageID {
                        followsLatest = true
                        proxy.scrollTo(latestScrollTargetID ?? newest, anchor: .bottom)
                    }
                    pinnedBeforeKeyboardResize = nil
                }
                .onPreferenceChange(ChatThreadBottomKey.self) { bottom in
                    // Only an actual scroll can release automatic following.
                    // Lazy rows/insets can temporarily have no end geometry
                    // during initial loading or keyboard/composer resizing.
                    guard readerHasScrolled else { followsLatest = true; return }
                    guard let bottom else { followsLatest = false; return }
                    if pinnedBeforeKeyboardResize == true && !dragging { return }
                    let visibleBottom = max(0, viewport.size.height - viewport.safeAreaInsets.bottom)
                    followsLatest = bottom >= 0 && bottom <= visibleBottom + 64
                }
                .onChange(of: latestMessageID, initial: true) { old, newest in
                    guard let newest, readingActive,
                          old == newest || old == nil || followsLatest || latestIsOwn else { return }
                    followsLatest = true
                    readerHasScrolled = false
                    if pinnedBeforeKeyboardResize != nil && !dragging {
                        pinnedBeforeKeyboardResize = true
                    }
                    // No animation across long histories on initial load.
                    // Target a real row in the SAME lazy stack as the end
                    // marker. An external sentinel can scroll past the rows
                    // before SwiftUI has materialized them with bottom insets.
                    proxy.scrollTo(latestScrollTargetID ?? newest, anchor: .bottom)
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
