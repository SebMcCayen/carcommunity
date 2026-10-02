import SwiftUI

struct PointsScreen: View {
    @State private var coordinator: PointsCoordinator

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
            VStack(alignment: .leading, spacing: KccSpacing.s4) {
                balanceCard
                Text("points.noTransfer")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text("points.recentTransactions")
                    .font(.headline)
                entries
            }
            .padding(KccSpacing.s6)
        }
        .navigationTitle("points.screenTitle")
        .background(.background)
        .task { coordinator.start() }
    }

    private var balanceCard: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            Text("points.balanceLabel")
                .font(.subheadline)
            Text(verbatim: String(coordinator.balance ?? 0))
                .font(.largeTitle.weight(.semibold))
        }
        .foregroundStyle(Color.primary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(KccSpacing.s5)
        .background(Color.accentColor.opacity(0.14), in: RoundedRectangle(cornerRadius: 16))
    }

    @ViewBuilder private var entries: some View {
        switch coordinator.entriesState {
        case .loading:
            ProgressView("points.loading").frame(maxWidth: .infinity)
        case .unavailable:
            Text("points.error").foregroundStyle(.secondary)
        case .failed:
            VStack(alignment: .leading, spacing: KccSpacing.s2) {
                Text("points.error").foregroundStyle(.red)
                Button("points.retry") { coordinator.reload() }
                    .buttonStyle(.bordered)
            }
        case .loaded(let entries):
            if entries.isEmpty {
                Text("points.empty").foregroundStyle(.secondary)
            } else {
                LazyVStack(spacing: KccSpacing.s3) {
                    ForEach(entries, id: \.id) { entry in
                        PointsEntryCard(entry: entry)
                    }
                }
            }
        }
    }
}

private struct PointsEntryCard: View {
    let entry: PointsEntry

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            Text(verbatim: entry.amount >= 0 ? "+\(entry.amount)" : String(entry.amount))
                .font(.headline)
                .foregroundStyle(entry.amount >= 0 ? Color.accentColor : Color.red)
            if !entry.description.isEmpty {
                Text(verbatim: entry.description).foregroundStyle(.secondary)
            }
            if let createdAt = entry.createdAt {
                Text(createdAt, format: .dateTime.year().month(.abbreviated).day())
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(KccSpacing.s4)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}
