import SwiftUI

/// The upcoming-events list — the iOS slice of Android's `EventsListScreen`
/// (upcoming tab only; past/create arrive with later slices). Tapping a row
/// pushes ``EventDetailScreen`` on this feature's own NavigationStack.
///
/// A dumb switch over ``EventsListUiState``: all decisions live in the pure
/// ``EventsCoordinator``. The `coordinator` is nil in a config-less build
/// (no GoogleService-Info.plist → ``FirebaseEventsRepository/createIfAvailable()``
/// returns nil); the screen then renders the placeholder state instead of
/// crashing — the same seam every Firebase-backed surface honors
/// (apps/ios/README.md, "Firebase configuration").
///
/// Reached from the Social hub panel via ``ShellRoute/events`` — see
/// ``ShellView``'s route host, which wraps this screen in a `NavigationStack`
/// and supplies the Back affordance.
struct EventsScreen: View {
    /// Nil in a config-less build; the screen degrades to a placeholder.
    let coordinator: EventsCoordinator?

    var body: some View {
        content
            .navigationTitle(Text("events.title"))
            .task { coordinator?.start() }
            // The list→detail push lives INSIDE the events feature: rows are
            // `NavigationLink(value:)`s and this destination resolves them on
            // the same NavigationStack that hosts the list — no shell wiring.
            .navigationDestination(for: EventDetailRoute.self) { route in
                EventDetailScreen(makeCoordinator: { [weak coordinator] in
                    coordinator?.makeDetailCoordinator(eventId: route.eventId)
                })
            }
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            switch coordinator.state {
            case .loading:
                loadingState
            case .empty:
                messageState(
                    title: "events.noUpcomingTitle",
                    body: "events.noUpcomingBody"
                )
            case .failed:
                errorState
            case .loaded(let events):
                list(events)
            }
        } else {
            // Config-less build: events are not wired in this build. The
            // closest existing contract key — an "unavailable" key does not
            // exist in the events.* vocabulary.
            messageState(title: "events.title", body: "events.placeholder")
        }
    }

    private var loadingState: some View {
        VStack(spacing: KccSpacing.s3) {
            ProgressView()
            Text("events.loading")
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var errorState: some View {
        VStack(spacing: KccSpacing.s3) {
            Text("events.error")
                .multilineTextAlignment(.center)
            Button {
                coordinator?.reload()
            } label: {
                Text("events.retry")
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func messageState(title: LocalizedStringKey, body: LocalizedStringKey) -> some View {
        VStack(spacing: KccSpacing.s2) {
            Text(title)
                .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.semibold))
            Text(body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func list(_ events: [EventSummary]) -> some View {
        List {
            Section {
                ForEach(events) { event in
                    NavigationLink(value: EventDetailRoute(eventId: event.id)) {
                        EventRow(event: event)
                    }
                }
            } header: {
                Text("events.screenSubtitle")
            }
        }
        .listStyle(.insetGrouped)
    }
}

/// The value a tapped list row pushes — resolved by ``EventsScreen``'s
/// `navigationDestination` into an ``EventDetailScreen``. Only the id
/// travels: the detail screen re-observes the teaser itself (live updates,
/// same as Android's `observeEvent(selected)`), rather than freezing the
/// row's snapshot.
struct EventDetailRoute: Hashable, Sendable {
    let eventId: String
}

/// One teaser row: title, start time, place, official badge, going tally —
/// the same teaser fields Android's upcoming list renders.
private struct EventRow: View {
    let event: EventSummary

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            HStack(spacing: KccSpacing.s2) {
                Text(event.title)
                    .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
                if event.isOfficial {
                    Text("events.officialBadge")
                        .font(.system(size: KccTypeScale.caption))
                        .padding(.horizontal, KccSpacing.s2)
                        .padding(.vertical, KccSpacing.s1 / 2)
                        .background(KccPalette.crownGold.opacity(0.2))
                        .clipShape(Capsule())
                }
            }
            if let startsAt = event.startsAt {
                Text(startsAt.formatted(date: .abbreviated, time: .shortened))
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(.secondary)
            }
            if let place = event.locationName ?? event.approximateArea {
                Text(place)
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(.secondary)
            }
            if event.counts.going > 0 {
                Text(goingCountText(event.counts.going))
                    .font(.system(size: KccTypeScale.caption))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, KccSpacing.s1)
    }

    /// "%1$lld going" (events.rowGoingCount) with the going tally.
    private func goingCountText(_ going: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("events.rowGoingCount", comment: "Going tally on an event row"),
            going
        )
    }
}

#Preview {
    NavigationStack {
        EventsScreen(coordinator: nil)
    }
}
