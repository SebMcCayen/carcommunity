import SwiftUI

/// Full-sized labels scroll horizontally; the reading area keeps the remaining height.
struct StatsReadingTabs<Selection: Hashable>: View {
    let options: [Selection]
    @Binding var selection: Selection
    let title: (Selection) -> LocalizedStringKey
    let identifier: String

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: KccSpacing.s2) {
                ForEach(options, id: \.self) { option in
                    Button { selection = option } label: {
                        Text(title(option)).font(.body.weight(.semibold)).fixedSize()
                            .padding(KccSpacing.s3).frame(minHeight: 48)
                            .foregroundStyle(selection == option ? KccTheme.onDriver : KccTheme.text)
                            .background(selection == option ? KccTheme.driver : KccTheme.subtle, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selection == option ? [.isSelected] : [])
                }
            }.padding(KccSpacing.s3)
        }
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
    }
}
