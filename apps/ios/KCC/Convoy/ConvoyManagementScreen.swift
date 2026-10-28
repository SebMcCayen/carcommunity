import SwiftUI

struct ConvoyManagementScreen: View {
    @Bindable var coordinator: ConvoyManagementCoordinator
    let onCreate: () -> Void
    let onJoined: (ConvoyItem) -> Void
    let onOpen: (ConvoyItem) -> Void

    var body: some View {
        content
            .navigationTitle("convoy.title")
            .kccReadingSurface(pageBackground: true)
            .tint(KccTheme.driver)
            .task { await coordinator.load() }
            .refreshable { await coordinator.load() }
    }

    @ViewBuilder
    private var content: some View {
        switch coordinator.state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .unavailable:
            SocialReadingState(title: "convoy.title", message: "convoy.errorGeneric", symbol: "car.2")
        case .failed(let error):
            ScrollView {
                SettingsStateCard(title: "convoy.title", message: ConvoyManagementStrings.errorKey(error), symbol: "exclamationmark.circle",
                    retryTitle: "convoy.friendsRetry", onRetry: { Task { await coordinator.load() } }).padding(KccSpacing.s4)
            }
        case .loaded(let snapshot): list(snapshot)
        }
    }

    private func list(_ snapshot: ConvoyManagementSnapshot) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                Button(action: onCreate) {
                    ConvoyCommandLabel(title: "convoy.createAction")
                }.kccPrimaryAction().disabled(!snapshot.canJoinAnotherConvoy)
                if snapshot.hasActiveConvoy {
                    Text("convoy.alreadyInConvoyCreateHint").font(.body).foregroundStyle(KccTheme.secondary)
                } else if !snapshot.isExhaustive {
                    Text("convoy.membershipUncertainHint").font(.body).foregroundStyle(KccTheme.secondary)
                    Button { Task { await coordinator.load() } } label: {
                        Text("convoy.friendsRetry").frame(minHeight: 48)
                    }.buttonStyle(.bordered)
                }
                if let error = coordinator.actionError {
                    ConvoyActionFeedback(error: error, onDismiss: coordinator.clearActionError)
                }
                if !snapshot.pendingInvites.isEmpty {
                    Text("convoy.invitesTitle").font(.title3.bold()).accessibilityAddTraits(.isHeader)
                    ForEach(snapshot.pendingInvites) { pendingInvite($0, snapshot: snapshot) }
                }
                Text("convoy.myConvoysTitle").font(.title3.bold()).accessibilityAddTraits(.isHeader)
                if snapshot.myConvoys.isEmpty {
                    Text("convoy.emptyMine").font(.body).foregroundStyle(KccTheme.secondary)
                } else {
                    ForEach(snapshot.myConvoys) { convoy in
                        Button { onOpen(convoy) } label: {
                            ConvoySummaryContent(convoy: convoy, showsDisclosure: true).kccContentCard().contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }
            }.padding(KccSpacing.s4)
        }
    }

    private func pendingInvite(_ convoy: ConvoyItem, snapshot: ConvoyManagementSnapshot) -> some View {
        let busy = coordinator.busyConvoyIds.contains(convoy.convoyId)
        return VStack(alignment: .leading, spacing: KccSpacing.s3) {
            ConvoySummaryContent(convoy: convoy)
            Text(verbatim: String.localizedStringWithFormat(
                NSLocalizedString("convoy.invitedByLabel", comment: "Convoy inviter"),
                convoy.ownerName ?? String(localized: "convoy.unknownMember")
            )).font(.body).foregroundStyle(KccTheme.secondary)
            SocialFlow {
                Button {
                    Task {
                        if let joined = await coordinator.respond(convoyId: convoy.convoyId, action: .accept) { onJoined(joined) }
                    }
                } label: { Text("convoy.accept").frame(minHeight: 48) }
                    .kccPrimaryAction().disabled(busy || !snapshot.canJoinAnotherConvoy)
                Button(role: .destructive) {
                    Task { await coordinator.respond(convoyId: convoy.convoyId, action: .decline) }
                } label: { Text("convoy.decline").frame(minHeight: 48) }
                    .buttonStyle(.bordered).tint(KccPalette.errorRed).disabled(busy)
                if busy { ProgressView() }
            }
            if snapshot.hasActiveConvoy {
                Text("convoy.alreadyInConvoyAcceptHint").font(.footnote).foregroundStyle(KccTheme.secondary)
            } else if !snapshot.isExhaustive {
                Text("convoy.membershipUncertainHint").font(.footnote).foregroundStyle(KccTheme.secondary)
            }
        }.kccContentCard()
    }
}
