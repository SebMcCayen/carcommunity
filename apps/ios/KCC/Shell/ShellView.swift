import SwiftUI

/// The five-tab, map-first shell. Placeholder content for now — each tab fills
/// in as its feature is ported; the tab set, default tab, and (eventually) the
/// map-cover rules come from the pure ``ShellNav`` logic so behaviour stays
/// unit-tested outside SwiftUI.
struct ShellView: View {
    @State private var selectedTab: ShellTab = .defaultTab

    var body: some View {
        TabView(selection: $selectedTab) {
            ForEach(ShellTab.allCases, id: \.self) { tab in
                placeholder(for: tab)
                    .tabItem { Label(tab.title, systemImage: tab.systemImage) }
                    .tag(tab)
            }
        }
    }

    @ViewBuilder
    private func placeholder(for tab: ShellTab) -> some View {
        VStack(spacing: 12) {
            Image(systemName: tab.systemImage)
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text(tab.title)
                .font(.title2)
        }
    }
}

extension ShellTab {
    /// Localized tab title. Keys live in the generated `Localizable.xcstrings`
    /// and are the same semantic names as `contracts/localization`
    /// (`shell.tabMap` …) — see `apps/ios/scripts/generate-strings.mjs`.
    var title: LocalizedStringKey {
        switch self {
        case .map: "shell.tabMap"
        case .history: "shell.tabHistory"
        case .create: "shell.tabCreate"
        case .social: "shell.tabSocial"
        case .garage: "shell.tabGarage"
        }
    }

    var systemImage: String {
        switch self {
        case .map: "map"
        case .history: "clock"
        case .create: "plus.circle"
        case .social: "person.2"
        case .garage: "car"
        }
    }
}

#Preview {
    ShellView()
}
