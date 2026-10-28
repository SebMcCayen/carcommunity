import SwiftUI

struct PointsScreen: View {
    @State private var coordinator: PointsCoordinator
    @State private var filter: PointsHistoryFilter = .all

    init(uid: String?) {
        _coordinator = State(initialValue: PointsCoordinator(
            repository: FirebasePointsRepository.createIfAvailable(), uid: uid
        ))
    }

    init(coordinator: PointsCoordinator) {
        _coordinator = State(initialValue: coordinator)
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                PointsBalanceCard(balance: coordinator.balance)
                Text("points.noTransfer").font(.footnote).foregroundStyle(KccTheme.secondary)
                StatsReadingTabs(options: PointsHistoryFilter.allCases, selection: $filter,
                    title: { $0.titleKey }, identifier: "points.filters")
                Text("points.recentTransactions").font(.title3.bold()).accessibilityAddTraits(.isHeader)
                entries
            }
            .padding(KccSpacing.s4)
        }
        .navigationTitle("points.screenTitle")
        .kccReadingSurface(pageBackground: true)
        .tint(KccTheme.driver)
        .task { coordinator.start() }
    }

    @ViewBuilder private var entries: some View {
        switch coordinator.entriesState {
        case .loading:
            ProgressView("points.loading").frame(maxWidth: .infinity).padding(KccSpacing.s4)
        case .unavailable:
            SettingsStateCard(title: "points.screenTitle", message: "points.error", symbol: "tray")
        case .failed:
            SettingsStateCard(title: "points.screenTitle", message: "points.error", symbol: "exclamationmark.circle",
                retryTitle: "points.retry", onRetry: { coordinator.reload() })
        case .loaded(let entries):
            let visible = filter.apply(entries)
            if visible.isEmpty {
                SettingsStateCard(title: "points.recentTransactions", message: "points.empty", symbol: "tray")
            } else {
                ForEach(visible, id: \.id) { entry in PointsEntryCard(entry: entry) }
            }
        }
    }
}

/// Filters only the already-bounded, owner-only snapshot. Zero corrections
/// remain in All; no additional query, transfer or re-ranking is introduced.
enum PointsHistoryFilter: CaseIterable, Hashable {
    case all, credits, debits

    var titleKey: LocalizedStringKey {
        switch self {
        case .all: "crownHunt.ledgerAll"
        case .credits: "crownHunt.ledgerCredits"
        case .debits: "crownHunt.ledgerDebits"
        }
    }

    func apply(_ entries: [PointsEntry]) -> [PointsEntry] {
        entries.filter { entry in
            switch self {
            case .all: true
            case .credits: entry.amount > 0
            case .debits: entry.amount < 0
            }
        }
    }
}

struct PointsBalanceCard: View {
    let balance: Int64?

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Label("points.balanceLabel", systemImage: "crown.fill")
                .font(.headline).foregroundStyle(KccTheme.driver)
            if let balance {
                Text(verbatim: String.localizedStringWithFormat(
                    NSLocalizedString("crownHunt.kpValue", comment: "Actual points balance"), balance
                ))
                .font(.largeTitle.bold()).fixedSize(horizontal: false, vertical: true)
            } else {
                Text("points.loading").font(.body).foregroundStyle(KccTheme.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }
}

struct PointsEntryCard: View {
    let entry: PointsEntry

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text(verbatim: String.localizedStringWithFormat(
                NSLocalizedString("crownHunt.ledgerAmount", comment: "Signed points change"),
                entry.amount >= 0 ? "+\(entry.amount)" : String(entry.amount)
            ))
            .font(.title3.bold())
            .foregroundStyle(entry.amount >= 0 ? KccTheme.text : KccPalette.errorRed)
            .fixedSize(horizontal: false, vertical: true)
            if !entry.description.isEmpty {
                Text(verbatim: entry.description).font(.body).fixedSize(horizontal: false, vertical: true)
            }
            if let createdAt = entry.createdAt {
                Text(createdAt, format: .dateTime.year().month(.abbreviated).day().hour().minute())
                    .font(.footnote).foregroundStyle(KccTheme.secondary)
            }
            if let balance = entry.balanceAfter {
                Text(verbatim: String.localizedStringWithFormat(
                    NSLocalizedString("crownHunt.ledgerBalanceAfter", comment: "Balance after this entry"), balance
                ))
                .font(.footnote).foregroundStyle(KccTheme.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
        .accessibilityElement(children: .combine)
    }
}
