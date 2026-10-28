import SwiftUI

/// Persistent labels keep optional and filled fields identifiable. Only the
/// input viewport is bounded; the full bound draft remains intact.
struct EditorTextField: View {
    let title: LocalizedStringKey
    @Binding var text: String
    var multiline = false
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            Text(title).font(.footnote).foregroundStyle(KccTheme.secondary).accessibilityHidden(true)
            TextField(text: $text, axis: multiline ? .vertical : .horizontal) { Text(title) }
                .font(.body)
                .lineLimit(multiline ? (typeSize.isAccessibilitySize ? 1...2 : 3...6) : 1...1)
                .padding(.vertical, KccSpacing.s2).frame(minHeight: 48)
        }
    }
}

struct EditorSelectionLabel: View {
    let title: LocalizedStringKey
    let value: Text?
    let prompt: LocalizedStringKey

    var body: some View {
        HStack(alignment: .center, spacing: KccSpacing.s3) {
            VStack(alignment: .leading, spacing: KccSpacing.s2) {
                Text(title).font(.footnote).foregroundStyle(KccTheme.secondary)
                Group {
                    if let value { value }
                    else { Text(prompt) }
                }
                .font(.body).foregroundStyle(KccTheme.text).fixedSize(horizontal: false, vertical: true)
            }.frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold))
                .foregroundStyle(KccTheme.secondary).accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading).contentShape(Rectangle())
    }
}

struct EditorSubmitLabel: View {
    let title: LocalizedStringKey
    var saving = false

    var body: some View {
        HStack(spacing: KccSpacing.s2) {
            if saving { ProgressView().accessibilityHidden(true) }
            Text(title).font(.body.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
    }
}
