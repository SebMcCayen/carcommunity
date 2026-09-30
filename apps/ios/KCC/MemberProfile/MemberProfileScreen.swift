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
                    Button("blocking.unblock") { confirmUnblock = true }
                        .buttonStyle(.borderedProminent)
                        .disabled(coordinator.action != nil)
                }
            }
        case .unavailable:
            notice("memberProfile.unavailable") { EmptyView() }
        case .failed:
            notice("memberProfile.error") {
                Button("memberProfile.retry") { Task { await coordinator.load() } }
                    .buttonStyle(.borderedProminent)
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
        VStack(spacing: KccSpacing.s3) {
            Image(systemName: "person.crop.circle.badge.exclamationmark")
                .font(.system(size: 44)).foregroundStyle(.secondary)
            Text(key).multilineTextAlignment(.center).foregroundStyle(.secondary)
            actions()
            if coordinator.moderationFailed {
                Text("blocking.errorGeneric").foregroundStyle(KccPalette.errorRed)
            }
        }
        .padding(KccSpacing.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func profile(_ content: MemberProfileContent) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s5) {
                header(content)
                relationshipActions(content.profile)
                if let bio = content.profile.bio.trimmedNonBlank {
                    Text(verbatim: bio).font(.system(size: KccTypeScale.bodyMd))
                }
                socialLinks(content.profile)
                statistics(content)
                vehicles(content.vehicles)
                badges(content.badges)
                if coordinator.canModerate {
                    Button(role: .destructive) { confirmBlock = true } label: {
                        Label("blocking.blockUser", systemImage: "person.crop.circle.badge.xmark")
                    }
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
            .padding(KccSpacing.s5)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .refreshable { await coordinator.load() }
    }

    private func header(_ content: MemberProfileContent) -> some View {
        VStack(spacing: KccSpacing.s2) {
            Group {
                if let url = coordinator.avatarURL {
                    AsyncImage(url: url) { image in image.resizable().scaledToFill() }
                        placeholder: { Image(systemName: "person.crop.circle.fill") }
                } else {
                    Image(systemName: "person.crop.circle.fill")
                        .resizable().foregroundStyle(.secondary)
                }
            }
            .frame(width: 92, height: 92).clipShape(Circle())
            Text(verbatim: content.profile.displayName.trimmedNonBlank
                 ?? String(localized: "memberProfile.unknownMember"))
                .font(.system(size: KccTypeScale.headingLg, weight: .semibold))
            HStack(spacing: KccSpacing.s1) {
                Image(systemName: "crown.fill").foregroundStyle(KccPalette.crownGold)
                Text(verbatim: FriendPointsFormat.grouped(content.pointsBalance))
                    .fontWeight(.semibold)
                Text("profile.pointsTitle").foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func relationshipActions(_ profile: MemberProfile) -> some View {
        let disabled = coordinator.action != nil
        HStack(spacing: KccSpacing.s2) {
            switch coordinator.relationship {
            case .unknown:
                EmptyView()
            case .none:
                Button("memberProfile.addFriend") { Task { await coordinator.addFriend() } }
                    .buttonStyle(.borderedProminent).disabled(disabled)
            case .outgoingPending:
                Button("memberProfile.cancelRequest") { Task { await coordinator.cancelRequest() } }
                    .buttonStyle(.bordered).disabled(disabled)
            case .incomingPending:
                Button("friends.accept") { Task { await coordinator.respond(accept: true) } }
                    .buttonStyle(.borderedProminent).disabled(disabled)
                Button("friends.decline", role: .destructive) {
                    Task { await coordinator.respond(accept: false) }
                }.buttonStyle(.bordered).disabled(disabled)
            case .friends:
                if let onMessage {
                    Button("friends.message") { onMessage(profile.uid, profile.displayName) }
                        .buttonStyle(.borderedProminent)
                }
                Button("friends.remove", role: .destructive) { confirmUnfriend = true }
                    .buttonStyle(.bordered).disabled(disabled)
            }
        }
        if case .outgoingPending = coordinator.relationship {
            Text("memberProfile.requestPending").font(.caption).foregroundStyle(.secondary)
        } else if case .incomingPending = coordinator.relationship {
            Text("memberProfile.wantsToBeFriends").font(.caption).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func socialLinks(_ profile: MemberProfile) -> some View {
        let links = MemberSocialLinks.links(profile)
        if !links.isEmpty {
            HStack(spacing: KccSpacing.s3) {
                ForEach(links, id: \.label) { link in
                    Button(link.label) { openURL(link.url) }.buttonStyle(.bordered)
                }
            }
        }
    }

    private func statistics(_ content: MemberProfileContent) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("memberProfile.statsTitle")
                .font(.system(size: KccTypeScale.titleMd, weight: .semibold))
            if let joined = content.profile.createdAt {
                HStack {
                    Text("memberProfile.statsMemberSince")
                    Spacer()
                    Text(joined, format: .dateTime.month(.wide).year())
                }
            }
        }
    }

    private func vehicles(_ vehicles: [Vehicle]) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("memberProfile.carsTitle")
                .font(.system(size: KccTypeScale.titleMd, weight: .semibold))
            if vehicles.isEmpty {
                Text("memberProfile.carsEmpty").foregroundStyle(.secondary)
            } else {
                ForEach(vehicles) { vehicle in
                    VStack(alignment: .leading, spacing: KccSpacing.s1) {
                        HStack {
                            Text(verbatim: VehicleDisplay.headline(
                                vehicle,
                                otherLabel: String(localized: "garage.otherNotListed")
                            )).fontWeight(.semibold)
                            if vehicle.isMainCar {
                                Text("memberProfile.mainCar").font(.caption)
                                    .padding(.horizontal, KccSpacing.s2)
                                    .background(KccPalette.crownGold.opacity(0.2), in: Capsule())
                            }
                        }
                        Text(LocalizedStringKey(vehicle.powertrain.localizationKey))
                            .foregroundStyle(.secondary)
                        if let plate = vehicle.registrationPlate.trimmedNonBlank {
                            Text(verbatim: String.localizedStringWithFormat(
                                NSLocalizedString("memberProfile.registrationPlate", comment: "Public plate"), plate
                            )).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .padding(KccSpacing.s3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: KccRadius.md))
                }
            }
        }
    }

    private func badges(_ badges: PublicBadges) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("memberProfile.badgesTitle")
                .font(.system(size: KccTypeScale.titleMd, weight: .semibold))
            switch badges {
            case .unavailable: Text("memberProfile.badgesUnavailable").foregroundStyle(.secondary)
            case .failed: Text("memberProfile.badgesLoadError").foregroundStyle(.secondary)
            case .available(let values):
                if values.isEmpty { Text("memberProfile.badgesEmpty").foregroundStyle(.secondary) }
                else {
                    ForEach(values, id: \.key) { badge in
                        Label {
                            if let key = BadgeStrings.badgeNameKey(for: badge.key) {
                                Text(LocalizedStringKey(key))
                            } else { Text(verbatim: badge.fallbackName ?? badge.key) }
                        } icon: { Image(systemName: "medal.fill").foregroundStyle(KccPalette.crownGold) }
                    }
                }
            }
        }
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
