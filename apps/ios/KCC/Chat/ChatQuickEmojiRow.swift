import SwiftUI

/// Same ordered one-tap messages as Android's shared ChatQuickEmojis. A quick
/// send goes through the caller's normal send path and never edits its draft.
struct ChatQuickEmojiRow: View {
    @Environment(\.messageReactionStore) private var reactions
    let enabled: Bool
    let onSelect: (String) -> Void
    private static let choices = [
        ("happy", "😊"), ("laughing", "😂"), ("sad", "😢"),
        ("thumbsUp", "👍"), ("heart", "❤️"), ("eyes", "👀"), ("crown", "👑")
    ]

    var body: some View {
        if reactions?.enabled != true {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: KccSpacing.s2) {
                ForEach(Self.choices, id: \.0) { key, glyph in
                    Button { onSelect(glyph) } label: {
                        Text(glyph).font(.system(size: 24))
                            .frame(width: 40, height: 40)
                            .background(KccTheme.subtle, in: Circle())
                            .frame(width: 48, height: 48)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!enabled)
                    .accessibilityLabel(Text(LocalizedStringKey("chat.quickEmoji." + key)))
                    .accessibilityIdentifier("chat.quickEmoji." + key)
                }
            }
            .padding(.horizontal, KccSpacing.s2)
        }
        .frame(height: 48)
        .scrollDismissesKeyboard(.never)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("chat.quickEmojiRow"))
        .accessibilityIdentifier("chat.quickEmojiRow")
        }
    }
}
