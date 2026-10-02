import SwiftUI

struct WhatsNewScreen: View {
    let entries: [ChangelogEntry]

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                Text("whatsNew.title")
                    .font(.system(size: KccTypeScale.headingLg, weight: .semibold))
                    .frame(maxWidth: .infinity, alignment: .leading)

                if entries.isEmpty {
                    Text("whatsNew.empty")
                        .foregroundStyle(.secondary)
                }

                ForEach(entries) { entry in
                    VStack(alignment: .leading, spacing: KccSpacing.s2) {
                        Text(verbatim: entryTitle(entry))
                            .font(.headline)
                            .foregroundStyle(.tint)
                        ForEach(entry.changeKeys, id: \.self) { key in
                            HStack(alignment: .firstTextBaseline, spacing: KccSpacing.s2) {
                                Text(verbatim: "•").accessibilityHidden(true)
                                Text(LocalizedStringKey(key))
                            }
                        }
                    }
                    .padding(KccSpacing.s4)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: KccRadius.lg))
                }
            }
            .padding(KccSpacing.s6)
        }
        .background(.background)
        .navigationTitle(Text("whatsNew.title"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func entryTitle(_ entry: ChangelogEntry) -> String {
        String(
            format: String(localized: "whatsNew.entryTitle"),
            locale: Locale.current,
            entry.versionName,
            entry.releaseDate
        )
    }
}

struct WhatsNewAnnouncementSheet: View {
    let announcement: UpdateAnnouncement
    let onShowAll: () -> Void
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: KccSpacing.s4) {
                Text(verbatim: title)
                    .font(.system(size: KccTypeScale.headingLg, weight: .semibold))
                ForEach(announcement.entry.highlightKeys.prefix(Changelog.popupHighlightLimit), id: \.self) { key in
                    HStack(alignment: .firstTextBaseline, spacing: KccSpacing.s2) {
                        Text(verbatim: "•").accessibilityHidden(true)
                        Text(LocalizedStringKey(key))
                    }
                }
                if announcement.includesEarlierVersions {
                    Text("whatsNew.dialogMoreVersions")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button("whatsNew.dialogShowAll", action: onShowAll)
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
                Button("whatsNew.dialogClose", action: onClose)
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
            }
            .padding(KccSpacing.s6)
            .presentationDetents([.medium])
            .interactiveDismissDisabled()
        }
    }

    private var title: String {
        String(
            format: String(localized: "whatsNew.dialogTitle"),
            locale: Locale.current,
            announcement.entry.versionName
        )
    }
}
