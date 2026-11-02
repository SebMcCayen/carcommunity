import SwiftUI

/// Native sheets already support drag-to-dismiss. Show that affordance and
/// retain the standard VoiceOver escape without duplicating it with Close.
struct DismissibleSheet: ViewModifier {
    @Environment(\.dismiss) private var dismiss
    func body(content: Content) -> some View {
        content
            .presentationDragIndicator(.visible)
            .accessibilityAction(.escape) { dismiss() }
    }
}

extension View {
    func kccDismissibleSheet() -> some View { modifier(DismissibleSheet()) }
}
