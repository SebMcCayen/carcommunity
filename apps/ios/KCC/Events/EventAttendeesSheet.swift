import SwiftUI

struct EventAttendeesSheet: View {
    @Environment(\.dismiss) private var dismiss
    let coordinator: EventDetailCoordinator

    var body: some View {
        NavigationStack {
            Group {
                switch coordinator.attendeesState {
                case .idle, .loading:
                    ProgressView("events.attendeesLoading")
                case .loaded(let attendees) where attendees.isEmpty:
                    message("events.attendeesEmpty")
                case .loaded(let attendees):
                    List {
                        ForEach(RsvpStatus.allCases, id: \.self) { status in
                            let members = attendees.filter { $0.status == status }
                            if !members.isEmpty {
                                Section(statusTitle(status)) {
                                    ForEach(members) { attendee in
                                        Label(
                                            attendee.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
                                                .nilIfEmpty ?? String(localized: "events.attendeesUnknownMember"),
                                            systemImage: "person.crop.circle"
                                        )
                                    }
                                }
                            }
                        }
                    }
                case .requiresPaid:
                    message("events.upgradeDetailsBody")
                case .unavailable:
                    message("events.attendeesUnavailable")
                case .failed:
                    VStack(spacing: KccSpacing.s3) {
                        message("events.attendeesError")
                        Button("events.retry") { coordinator.loadAttendees() }
                            .buttonStyle(.borderedProminent)
                    }
                }
            }
            .navigationTitle(Text("events.attendeesTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("events.attendeesClose") { dismiss() }
                }
            }
        }
    }

    private func message(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func statusTitle(_ status: RsvpStatus) -> LocalizedStringKey {
        switch status {
        case .going: "events.attendeesGroupGoing"
        case .maybe: "events.attendeesGroupMaybe"
        case .notGoing: "events.attendeesGroupNotGoing"
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
