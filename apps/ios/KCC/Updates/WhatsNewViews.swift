import SwiftUI

struct WhatsNewScreen: View {
    let entries: [ChangelogEntry]

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                Text("whatsNew.title")
                    .font(.title2.bold())
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
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .kccContentCard()
                }
            }
            .padding(KccSpacing.s4)
        }
        .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
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
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    Text(verbatim: title)
                        .font(.title2.bold())
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
                    Button(action: onShowAll) { Text("whatsNew.dialogShowAll").frame(maxWidth: .infinity, minHeight: 48) }
                        .kccPrimaryAction()
                        .frame(maxWidth: .infinity)
                }
                .padding(KccSpacing.s4)
            }
            .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
            .navigationTitle(Text("whatsNew.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) {
                Button(action: onClose) { Text("whatsNew.dialogClose").frame(minHeight: 48) }
            }}
            .presentationDetents([.medium, .large])
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
