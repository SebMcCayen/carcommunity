import SwiftUI

/// Balance and recent-credit summary on the signed-in member's profile. The
/// whole card is the discoverable, accessible door to the complete statement.
struct ProfilePointsSection: View {
    let balance: Int64?
    let recentEarnings: [PointsEntry]
    let onOpenLedger: (() -> Void)?

    var body: some View {
        Group {
            if let onOpenLedger {
                Button(action: onOpenLedger) { content(showLink: true) }
                    .buttonStyle(.plain)
                    .accessibilityHint(Text("profile.pointsViewAll"))
            } else {
                content(showLink: false)
            }
        }
        .accessibilityIdentifier("profile.pointsSummary")
    }

    private func content(showLink: Bool) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("profile.pointsTitle").font(.headline)
            HStack(alignment: .lastTextBaseline, spacing: KccSpacing.s1) {
                Text(verbatim: String(balance ?? 0))
                    .font(.title.weight(.semibold))
                Text("profile.pointsUnit")
                    .font(.headline)
                    .foregroundStyle(.secondary)
            }
            Text("profile.pointsSubtitle").font(.caption).foregroundStyle(.secondary)
            Divider()
            Text("profile.pointsRecentTitle").font(.subheadline.weight(.semibold))
            if recentEarnings.isEmpty {
                Text("profile.pointsRecentEmpty").font(.caption).foregroundStyle(.secondary)
            } else {
                ForEach(recentEarnings, id: \.id) { entry in
                    HStack(spacing: KccSpacing.s2) {
                        Text(verbatim: entry.description)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Text(PointsText.profileAmount(entry.amount))
                    }
                }
            }
            if showLink {
                HStack {
                    Text("profile.pointsViewAll")
                    Spacer()
                    Image(systemName: "chevron.forward").accessibilityHidden(true)
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.tint)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(KccSpacing.s4)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        .contentShape(Rectangle())
    }
}

enum PointsText {
    static func profileAmount(_ amount: Int64) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("profile.pointsAmount", comment: "Recent points credit"),
            amount
        )
    }
}
