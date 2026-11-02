import SwiftUI
import UIKit

private struct ChatKeyboardPresentedKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var chatKeyboardPresented: Bool {
        get { self[ChatKeyboardPresentedKey.self] }
        set { self[ChatKeyboardPresentedKey.self] = newValue }
    }
}

/// Observe the actual software keyboard, including dismissal by dragging.
/// Focus alone also includes hardware keyboards, which do not cover messages.
struct ChatKeyboardObserver: ViewModifier {
    @Binding var isPresented: Bool

    func body(content: Content) -> some View {
        content
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
                guard (note.userInfo?[UIResponder.keyboardIsLocalUserInfoKey] as? Bool) != false,
                      let screenFrame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect,
                      let window = UIApplication.shared.connectedScenes
                        .compactMap({ $0 as? UIWindowScene })
                        .filter({ $0.activationState == .foregroundActive })
                        .flatMap(\.windows).first(where: \.isKeyWindow) else { return }
                let frame = window.convert(screenFrame, from: window.screen.coordinateSpace)
                isPresented = Self.coversWindow(frame: frame, bounds: window.bounds)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                isPresented = false
            }
    }

    nonisolated static func coversWindow(frame: CGRect, bounds: CGRect) -> Bool {
        guard !frame.isNull, !frame.isInfinite, !frame.isEmpty else { return false }
        let overlap = bounds.intersection(frame)
        return !overlap.isNull && !overlap.isEmpty
    }
}

/// In the existing composer row, so dismissal does not consume another row
/// of the conversation. Resigning first responder leaves the draft intact.
struct ChatKeyboardDismissButton: View {
    @Environment(\.chatKeyboardPresented) private var keyboardPresented

    var body: some View {
        if keyboardPresented {
            Button {
                UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),
                    to: nil, from: nil, for: nil)
            } label: {
                Image(systemName: "keyboard.chevron.compact.down")
                    .font(.system(size: 20))
                    .frame(width: 44, height: 48)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(KccTheme.text)
            .accessibilityLabel(Text("chat.hideKeyboard"))
            .accessibilityIdentifier("chat.keyboard.dismiss")
        }
    }
}
