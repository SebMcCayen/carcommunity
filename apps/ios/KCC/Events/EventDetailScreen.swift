import SwiftUI

/// Event detail + RSVP — the iOS port of Android's `EventDetailScreen`
/// with member-gated private detail, RSVP, paid/admin attendee roster,
/// paid geofenced check-in, and creator edit/remove. Chat, share/calendar,
/// and event-linked group driving remain separate slices.
///
/// A dumb switch over the coordinator's state: all decisions live in the pure
/// ``EventDetailCoordinator``. The coordinator is created lazily on first
/// appearance from `makeCoordinator` (nil in a config-less build — the screen
/// then renders the same placeholder the list does, instead of crashing) and
/// kept in `@State` so a body re-evaluation never resets in-flight RSVP
/// state.
///
/// Reached by tapping a list row in ``EventsScreen`` — the push happens on
/// the events feature's own `NavigationStack` (the one hosting the list), so
/// no shell wiring is involved.
struct EventDetailScreen: View {
    @Environment(\.dismiss) private var dismiss
    /// Builds the coordinator on first appearance; returns nil in a
    /// config-less build.
    let makeCoordinator: @MainActor () -> EventDetailCoordinator?
    let locationProvider: any LocationProvider

    @State private var coordinator: EventDetailCoordinator?
    /// Whether the one-shot wiring ran — nil `coordinator` is also the
    /// legitimate steady state of a config-less build, so nil cannot mean
    /// "not attempted yet" (the same seam ``ShellView`` uses for the list).
    @State private var hasWired = false
    @State private var editCoordinator: EventFormCoordinator?
    @State private var showAttendees = false
    @State private var confirmRemove = false

    var body: some View {
        content
            .navigationTitle(titleText)
            .navigationBarTitleDisplayMode(.inline)
            .task {
                guard !hasWired else { return }
                hasWired = true
                coordinator = makeCoordinator()
                coordinator?.start()
            }
            .sheet(item: $editCoordinator) { editor in
                if let coordinator,
                   case .loaded(let event) = coordinator.state {
                    EventFormScreen(
                        coordinator: editor,
                        initial: EventFormInput(
                            title: event.title,
                            startsAt: event.startsAt ?? Date(),
                            description: coordinator.detail?.description,
                            address: coordinator.detail?.address,
                            latitude: event.latitude,
                            longitude: event.longitude,
                            publicSiteEnabled: false
                        ),
                        locationProvider: locationProvider
                    )
                }
            }
            .sheet(isPresented: $showAttendees) {
                if let coordinator { EventAttendeesSheet(coordinator: coordinator) }
            }
            .confirmationDialog(
                "events.removeTitle",
                isPresented: $confirmRemove,
                titleVisibility: .visible
            ) {
                Button("events.removeConfirmConfirm", role: .destructive) {
                    coordinator?.cancelEvent()
                }
                Button("events.removeConfirmCancel", role: .cancel) {}
            } message: {
                Text("events.removeConfirmMessage")
            }
            .onChange(of: coordinator?.manageState) { _, state in
                if state == .deleted { dismiss() }
            }
    }

    /// The event's title once loaded, the generic screen title otherwise —
    /// Android's `AeroPage(title = event?.title ?: events_title)`.
    private var titleText: Text {
        if case .loaded(let event) = coordinator?.state {
            return Text(event.title)
        }
        return Text("events.title")
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            switch coordinator.state {
            case .loading:
                messageState("events.loadingDetail")
            case .failed:
                errorState(coordinator)
            case .loaded(let event):
                detail(event, coordinator: coordinator)
            }
        } else {
            // Config-less build: degrade exactly like the list does.
            messageState("events.placeholder")
        }
    }

    private func messageState(_ body: LocalizedStringKey) -> some View {
        Text(body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// The teaser settled nil — missing or unreadable. Android renders
    /// `events_errorDetail` plus the shared retry.
    private func errorState(_ coordinator: EventDetailCoordinator) -> some View {
        VStack(spacing: KccSpacing.s3) {
            Text("events.errorDetail")
                .multilineTextAlignment(.center)
            Button {
                coordinator.reload()
            } label: {
                Text("events.retry")
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func detail(_ event: EventSummary, coordinator: EventDetailCoordinator) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                if event.isOfficial {
                    Text("events.officialBadge")
                        .font(.system(size: KccTypeScale.caption, weight: KccTypeScale.semibold))
                        .foregroundStyle(KccPalette.crownGold)
                }
                if event.status == .cancelled {
                    Text("events.cancelledNotice")
                        .font(.system(size: KccTypeScale.bodyMd))
                        .foregroundStyle(KccPalette.errorRed)
                }
                if let startsAt = event.startsAt {
                    // Android: DateFormat.FULL date + SHORT time.
                    Text(startsAt.formatted(date: .complete, time: .shortened))
                        .font(.system(size: KccTypeScale.bodyMd))
                        .foregroundStyle(.secondary)
                }
                if let area = trimmed(event.approximateArea) {
                    Text(area)
                        .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
                }
                // PUBLIC place name (teaser data since the 2026-07 open-up),
                // so it sits OUTSIDE the member gate below: a non-member must
                // still see where the event is. Only the precise street
                // address and the long description stay member-only.
                if let place = trimmed(event.locationName) {
                    Text(place)
                        .font(.system(size: KccTypeScale.bodyMd))
                        .foregroundStyle(.secondary)
                }
                if let summary = trimmed(event.summary) {
                    Text(summary)
                        .font(.system(size: KccTypeScale.bodyMd))
                        .foregroundStyle(.secondary)
                }

                // The member-gated detail, or the membership gate. Shown only
                // when the rules would actually serve it (gate + published);
                // a gate-passer on a non-published event sees neither (the
                // cancelled notice above already explains the state).
                if coordinator.canSeeDetails {
                    detailCard(coordinator.detail)
                } else if !coordinator.passesMemberGate {
                    infoCard(
                        title: "events.memberRequiredTitle",
                        body: "events.memberRequiredBody"
                    )
                }

                if coordinator.canRsvp {
                    rsvpSection(event, coordinator: coordinator)
                }

                attendeesButton(event, coordinator: coordinator)

                if coordinator.canCheckIn || coordinator.checkInState != .idle
                    || coordinator.checkInPending || coordinator.checkInVerified {
                    checkInSection(coordinator)
                }

                if coordinator.canManage {
                    creatorActions(coordinator)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(KccSpacing.s4)
        }
    }

    private func attendeesButton(
        _ event: EventSummary,
        coordinator: EventDetailCoordinator
    ) -> some View {
        Button {
            showAttendees = true
            coordinator.loadAttendees()
        } label: {
            Label(attendeeCountText(event.counts.going), systemImage: "person.2")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
    }

    private func attendeeCountText(_ count: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("events.attendeesCount", comment: "RSVP attendee count"), count
        )
    }

    private func checkInSection(_ coordinator: EventDetailCoordinator) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("events.checkInTitle")
                .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
            if coordinator.checkInVerified {
                Label("events.checkInConfirmed", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(.green)
            } else {
                TimelineView(.periodic(from: .now, by: 1)) { timeline in
                    let ready = coordinator.checkInAnchor.map {
                        Events.checkInRemaining(from: $0, now: timeline.date) == 0
                    } ?? false
                    if coordinator.canCheckIn {
                        Button(ready ? "events.checkInConfirmButton" : "events.checkInButton") {
                            coordinator.checkIn()
                        }
                        .disabled(coordinator.checkInState == .working)
                        .buttonStyle(.borderedProminent)
                    }
                    if coordinator.checkInState == .working {
                        ProgressView()
                    }
                    if coordinator.checkInPending {
                        checkInProgress(coordinator, now: timeline.date)
                    } else if coordinator.canCheckIn {
                        Text("events.checkInWithinArea").foregroundStyle(.secondary)
                    }
                    if case .failed(let error) = coordinator.checkInState {
                        let finalGeofenceMiss = error == .outsideGeofence && ready
                            && coordinator.checkInPending && coordinator.canCheckIn
                        Text(finalGeofenceMiss
                             ? "events.checkInMoveBackToFinish"
                             : checkInErrorKey(error))
                            .foregroundStyle(KccPalette.errorRed)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(KccSpacing.s3)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: KccSpacing.s2))
    }

    @ViewBuilder
    private func checkInProgress(_ coordinator: EventDetailCoordinator, now: Date) -> some View {
        if coordinator.canCheckIn, let anchor = coordinator.checkInAnchor {
            let remaining = Events.checkInRemaining(from: anchor, now: now)
            if remaining > 0 {
                ProgressView(value: Events.checkInProgress(from: anchor, now: now))
                    .accessibilityLabel(Text("events.checkInCountdownLabel"))
                Text(checkInCountdown(remaining)).foregroundStyle(.secondary)
            } else {
                Text("events.checkInReadyToConfirm")
            }
        } else {
            Text("events.checkInPending").foregroundStyle(.secondary)
        }
    }

    private func checkInCountdown(_ remaining: TimeInterval) -> String {
        let seconds = Int(ceil(max(0, remaining)))
        let clock = String(format: "%d:%02d", seconds / 60, seconds % 60)
        return String.localizedStringWithFormat(
            NSLocalizedString("events.checkInCountdown", comment: "Check-in dwell countdown"),
            clock
        )
    }

    private func checkInErrorKey(_ error: EventCheckInFailure) -> LocalizedStringKey {
        switch error {
        case .windowClosed: "events.checkInErrorWindow"
        case .positionUnavailable: "events.checkInErrorLocation"
        case .mockLocation: "events.checkInErrorMock"
        case .outsideGeofence: "events.checkInErrorGeofence"
        case .notCheckinable, .generic: "events.checkInErrorGeneric"
        }
    }

    private func creatorActions(_ coordinator: EventDetailCoordinator) -> some View {
        VStack(spacing: KccSpacing.s2) {
            Button("events.editButton") {
                editCoordinator = coordinator.makeEditCoordinator()
            }
            .buttonStyle(.bordered)
            .frame(maxWidth: .infinity)
            .disabled(!coordinator.detailSettled)
            Button("events.removeButton", role: .destructive) {
                confirmRemove = true
            }
            .buttonStyle(.bordered)
            .disabled(coordinator.manageState == .deleting)
            if case .failed(let reason) = coordinator.manageState {
                Text(removeErrorKey(reason)).foregroundStyle(KccPalette.errorRed)
            }
        }
    }

    private func removeErrorKey(_ reason: ManageEventFailure) -> LocalizedStringKey {
        switch reason {
        case .permissionDenied: "events.removeErrorPermission"
        case .immutable: "events.removeErrorImmutable"
        case .unknown: "events.removeError"
        }
    }

    /// The member-only detail card: precise street address + long
    /// description under the "Description" header (Android's `DetailCard`).
    /// The public place name is NOT repeated here — it renders above the
    /// gate.
    private func detailCard(_ detail: EventDetail?) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            Text("events.memberDetailPlaceholder")
                .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
            if let address = detail?.address {
                Text(address)
                    .font(.system(size: KccTypeScale.bodyMd))
                    .foregroundStyle(.secondary)
            }
            if let description = trimmed(detail?.description) {
                Text(description)
                    .font(.system(size: KccTypeScale.bodyMd))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(KccSpacing.s3)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: KccSpacing.s2))
    }

    /// The membership gate (Android's `InfoCard` with the member-required
    /// copy). While member gating is disabled this never renders — the copy
    /// IS the block, not a hint beside the detail.
    private func infoCard(title: LocalizedStringKey, body: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            Text(title)
                .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
            Text(body)
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(KccSpacing.s3)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: KccSpacing.s2))
    }

    /// RSVP: the three answer buttons (selection driven by the OBSERVED rsvp
    /// document, buttons disabled while a write is in flight), the public
    /// counts breakdown, and the transient failure line — Android's RSVP row
    /// + `RsvpCountsBreakdown`, with the shell snackbar replaced by an inline
    /// message (this screen owns no snackbar host).
    private func rsvpSection(_ event: EventSummary, coordinator: EventDetailCoordinator) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("events.rsvpCountsLabel")
                .font(.system(size: KccTypeScale.caption, weight: KccTypeScale.semibold))
                .foregroundStyle(.secondary)
            HStack(spacing: KccSpacing.s2) {
                rsvpButton("events.rsvpGoing", answer: .going, coordinator: coordinator)
                rsvpButton("events.rsvpMaybe", answer: .maybe, coordinator: coordinator)
                rsvpButton("events.rsvpNotGoing", answer: .notGoing, coordinator: coordinator)
            }
            if case .failed = coordinator.rsvpState {
                Text("events.rsvpSubmitError")
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(KccPalette.errorRed)
            }
            // How many answered each way, from the server-maintained public
            // rsvpCounts tally — deliberately NEUTRAL: three equal-weight
            // counts, no "winning" answer.
            HStack(spacing: KccSpacing.s2) {
                rsvpCount("events.rsvpGoing", count: event.counts.going)
                rsvpCount("events.rsvpMaybe", count: event.counts.maybe)
                rsvpCount("events.rsvpNotGoing", count: event.counts.notGoing)
            }
        }
    }

    /// One RSVP answer button — filled when it is the caller's current
    /// answer, outlined otherwise; both disabled while saving (Android's
    /// `RsvpButton`).
    @ViewBuilder
    private func rsvpButton(
        _ label: LocalizedStringKey,
        answer: RsvpStatus,
        coordinator: EventDetailCoordinator
    ) -> some View {
        let button = Button {
            coordinator.submitRsvp(answer)
        } label: {
            Text(label)
                .font(.system(size: KccTypeScale.bodySm))
                .frame(maxWidth: .infinity)
        }
        .disabled(coordinator.rsvpState == .saving)

        if coordinator.myRsvp == answer {
            button.buttonStyle(.borderedProminent)
        } else {
            button.buttonStyle(.bordered)
        }
    }

    /// One count in the breakdown: the tally over its answer label
    /// (Android's `RsvpCountItem`).
    private func rsvpCount(_ label: LocalizedStringKey, count: Int) -> some View {
        VStack(spacing: KccSpacing.s1 / 2) {
            Text("\(count)")
                .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
            Text(label)
                .font(.system(size: KccTypeScale.caption))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
    }

    /// Blank-insensitive optional text (Android's `takeIf { it.isNotBlank() }`,
    /// returning the trimmed text for display).
    private func trimmed(_ value: String?) -> String? {
        guard let value else { return nil }
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }
}

#Preview {
    NavigationStack {
        EventDetailScreen(
            makeCoordinator: { nil },
            locationProvider: StubLocationProvider()
        )
    }
}
