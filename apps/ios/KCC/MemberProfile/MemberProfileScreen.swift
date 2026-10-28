import SwiftUI

struct MemberProfileScreen: View {
    @Environment(\.openURL) private var openURL
    @State private var coordinator: MemberProfileCoordinator
    let onMessage: ((String, String?) -> Void)?

    @State private var confirmBlock = false
    @State private var confirmUnblock = false
    @State private var confirmUnfriend = false

    init(
        targetUid: String,
        viewerUid: String,
        friends: FriendsRepository?,
        onMessage: ((String, String?) -> Void)? = nil
    ) {
        _coordinator = State(initialValue: MemberProfileCoordinator(
            targetUid: targetUid,
            viewerUid: viewerUid,
            repository: FirebaseMemberProfileRepository.createIfAvailable(),
            friends: friends,
            blocking: FirebaseBlockingRepository.createIfAvailable()
        ))
        self.onMessage = onMessage
    }

    init(coordinator: MemberProfileCoordinator, onMessage: ((String, String?) -> Void)? = nil) {
        _coordinator = State(initialValue: coordinator)
        self.onMessage = onMessage
    }

    var body: some View {
        content
            .kccReadingSurface(pageBackground: true)
            .tint(KccTheme.driver)
            .navigationTitle("memberProfile.title")
            .navigationBarTitleDisplayMode(.inline)
            .task { await coordinator.load() }
            .confirmationDialog(
                "blocking.blockConfirmTitle",
                isPresented: $confirmBlock,
                titleVisibility: .visible
            ) {
                Button("blocking.blockConfirmAction", role: .destructive) {
                    Task { await coordinator.block() }
                }
                Button("blocking.blockCancelAction", role: .cancel) {}
            } message: { Text("blocking.blockConfirmBody") }
            .confirmationDialog(
                Text(verbatim: unfriendConfirmationTitle),
                isPresented: $confirmUnfriend,
                titleVisibility: .visible
            ) {
                Button("friends.removeConfirmAction", role: .destructive) {
                    Task { await coordinator.unfriend() }
                }
                Button("friends.removeCancel", role: .cancel) {}
            } message: { Text("friends.removeConfirmBody") }
            .confirmationDialog(
                "blocking.unblockConfirmTitle",
                isPresented: $confirmUnblock,
                titleVisibility: .visible
            ) {
                Button("blocking.unblockConfirmAction", role: .destructive) {
                    Task { await coordinator.unblock() }
                }
                Button("blocking.unblockCancelAction", role: .cancel) {}
            } message: { Text("blocking.unblockConfirmBody") }
    }

    @ViewBuilder
    private var content: some View {
        switch coordinator.state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .blocked:
            notice("memberProfile.blocked") {
                if coordinator.canModerate {
                    Button { confirmUnblock = true } label: {
                        Text("blocking.unblock").frame(minHeight: 48)
                    }.kccPrimaryAction()
                        .disabled(coordinator.action != nil)
                }
            }
        case .unavailable:
            notice("memberProfile.unavailable") { EmptyView() }
        case .failed:
            notice("memberProfile.error") {
                Button { Task { await coordinator.load() } } label: {
                    Text("memberProfile.retry").frame(minHeight: 48)
                }.kccPrimaryAction()
            }
        case .loaded(let content):
            profile(content)
        }
    }

    private var unfriendConfirmationTitle: String {
        let name: String
        if case .loaded(let content) = coordinator.state {
            name = content.profile.displayName.trimmedNonBlank
                ?? String(localized: "memberProfile.unknownMember")
        } else {
            name = String(localized: "memberProfile.unknownMember")
        }
        return String.localizedStringWithFormat(
            NSLocalizedString(
                "friends.removeConfirmTitle",
                comment: "Unfriend confirmation title with the member's name"
            ),
            name
        )
    }

    private func notice<Actions: View>(
        _ key: LocalizedStringKey,
        @ViewBuilder actions: () -> Actions
    ) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                Image(systemName: "person.crop.circle.badge.exclamationmark")
                    .font(.largeTitle).foregroundStyle(KccTheme.driver).accessibilityHidden(true)
                Text(key).font(.body).foregroundStyle(KccTheme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                actions()
                if coordinator.moderationFailed {
                    Text("blocking.errorGeneric").foregroundStyle(KccPalette.errorRed)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .kccContentCard()
            .padding(KccSpacing.s4)
        }
    }

    private func profile(_ content: MemberProfileContent) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                MemberProfileHeader(profile: content.profile, pointsBalance: content.pointsBalance, avatarURL: coordinator.avatarURL)
                relationshipActions(content.profile)
                socialLinks(content.profile)
                if let joined = content.profile.createdAt {
                    statistics(joined: joined)
                }
                vehicles(content.vehicles)
                badges(content.badges)
                if coordinator.canModerate {
                    Button(role: .destructive) { confirmBlock = true } label: {
                        Label("blocking.blockUser", systemImage: "person.crop.circle.badge.xmark")
                            .frame(minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .disabled(coordinator.action != nil)
                }
                if let error = coordinator.actionError {
                    Text(FriendsScreenStrings.actionErrorKey(error))
                        .foregroundStyle(KccPalette.errorRed)
                }
                if coordinator.moderationFailed {
                    Text("blocking.errorGeneric").foregroundStyle(KccPalette.errorRed)
                }
            }
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .refreshable { await coordinator.load() }
    }

    @ViewBuilder
    private func relationshipActions(_ profile: MemberProfile) -> some View {
        let disabled = coordinator.action != nil
        SocialFlow {
            switch coordinator.relationship {
            case .unknown:
                EmptyView()
            case .none:
                Button { Task { await coordinator.addFriend() } } label: {
                    Text("memberProfile.addFriend").frame(minHeight: 48)
                }.kccPrimaryAction().disabled(disabled)
            case .outgoingPending:
                Button { Task { await coordinator.cancelRequest() } } label: {
                    Text("memberProfile.cancelRequest").frame(minHeight: 48)
                }.buttonStyle(.bordered).disabled(disabled)
            case .incomingPending:
                Button { Task { await coordinator.respond(accept: true) } } label: {
                    Text("friends.accept").frame(minHeight: 48)
                }.kccPrimaryAction().disabled(disabled)
                Button(role: .destructive) {
                    Task { await coordinator.respond(accept: false) }
                } label: { Text("friends.decline").frame(minHeight: 48) }.buttonStyle(.bordered).disabled(disabled)
            case .friends:
                if let onMessage {
                    Button { onMessage(profile.uid, profile.displayName) } label: {
                        Text("friends.message").frame(minHeight: 48)
                    }.kccPrimaryAction()
                        .disabled(disabled)
                }
                Button(role: .destructive) { confirmUnfriend = true } label: {
                    Text("friends.remove").frame(minHeight: 48)
                }.buttonStyle(.bordered).disabled(disabled)
            }
        }
        if case .outgoingPending = coordinator.relationship {
            Text("memberProfile.requestPending").font(.footnote).foregroundStyle(KccTheme.secondary)
        } else if case .incomingPending = coordinator.relationship {
            Text("memberProfile.wantsToBeFriends").font(.footnote).foregroundStyle(KccTheme.secondary)
        }
    }

    @ViewBuilder
    private func socialLinks(_ profile: MemberProfile) -> some View {
        let links = MemberSocialLinks.links(profile)
        if !links.isEmpty {
            SocialFlow {
                ForEach(links, id: \.label) { link in
                    Button { openURL(link.url) } label: {
                        Text(verbatim: link.label).frame(minHeight: 48)
                    }.buttonStyle(.bordered)
                }
            }
        }
    }

    private func statistics(joined: Date) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text("memberProfile.statsTitle").font(.title3.bold()).accessibilityAddTraits(.isHeader)
            SocialFlow {
                Text("memberProfile.statsMemberSince").foregroundStyle(KccTheme.secondary)
                SocialFlowSpacer()
                Text(joined, format: .dateTime.month(.wide).year())
            }.font(.body)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }

    private func vehicles(_ vehicles: [Vehicle]) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text("memberProfile.carsTitle").font(.title3.bold()).accessibilityAddTraits(.isHeader)
            if vehicles.isEmpty {
                Text("memberProfile.carsEmpty").foregroundStyle(KccTheme.secondary)
            } else {
                ForEach(vehicles) { vehicle in MemberVehicleSummary(vehicle: vehicle) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }

    private func badges(_ badges: PublicBadges) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text("memberProfile.badgesTitle").font(.title3.bold()).accessibilityAddTraits(.isHeader)
            switch badges {
            case .unavailable: Text("memberProfile.badgesUnavailable").foregroundStyle(KccTheme.secondary)
            case .failed: Text("memberProfile.badgesLoadError").foregroundStyle(KccTheme.secondary)
            case .available(let values):
                if values.isEmpty { Text("memberProfile.badgesEmpty").foregroundStyle(KccTheme.secondary) }
                else { ForEach(values, id: \.key) { badge in MemberBadgeSummary(badge: badge) } }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }

}

struct MemberSocialLink: Equatable, Sendable {
    let label: String
    let url: URL
}

enum MemberSocialLinks {
    static func links(_ profile: MemberProfile) -> [MemberSocialLink] {
        [
            make("Facebook", handle: profile.facebook, host: "www.facebook.com", pattern: "^[a-z0-9][a-z0-9.-]{0,49}$"),
            make("Instagram", handle: profile.instagram, host: "www.instagram.com", pattern: "^[a-z0-9_][a-z0-9._]{0,29}$"),
            make("YouTube", handle: profile.youtube, host: "www.youtube.com", pattern: "^[A-Za-z0-9][A-Za-z0-9._-]{2,29}$", prefix: "@"),
        ].compactMap { $0 }
    }

    private static func make(
        _ label: String, handle: String?, host: String, pattern: String, prefix: String = ""
    ) -> MemberSocialLink? {
        guard let value = handle?.trimmingCharacters(in: .whitespacesAndNewlines),
              value.range(of: pattern, options: .regularExpression) != nil
        else { return nil }
        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.path = "/\(prefix)\(value)"
        guard let url = components.url else { return nil }
        return MemberSocialLink(label: label, url: url)
    }
}
