import SwiftUI

/// The host owns the chat switch, localization, and opaque map cover.
struct DriverFeedScreen: View {
    let uid: String?
    let strings: DriverFeedStrings
    let isActive: Bool
    private let repository: (any DriverFeedRepository)?

    init(uid: String?, strings: DriverFeedStrings, isActive: Bool = true,
        repository: (any DriverFeedRepository)? = FirebaseDriverFeedRepository.createIfAvailable()) {
        self.uid = uid
        self.strings = strings
        self.isActive = isActive
        self.repository = repository
    }

    var body: some View {
        DriverFeedSession(uid: uid, strings: strings, isActive: isActive, repository: repository).id(uid)
    }
}

private struct DriverFeedSession: View {
    let uid: String?
    let strings: DriverFeedStrings
    let isActive: Bool
    let repository: (any DriverFeedRepository)?
    @State private var coordinator: DriverFeedCoordinator
    @Environment(\.scenePhase) private var scenePhase

    init(uid: String?, strings: DriverFeedStrings, isActive: Bool, repository: (any DriverFeedRepository)?) {
        self.uid = uid
        self.strings = strings
        self.isActive = isActive
        self.repository = repository
        _coordinator = State(initialValue: DriverFeedCoordinator(repository: repository, uid: uid))
    }

    var body: some View {
        VStack(spacing: KccSpacing.s2) {
            SocialFlow {
                if coordinator.state.selectedId != nil {
                    Button(action: coordinator.back) { Label(strings["back"], systemImage: "chevron.left").frame(minHeight: 48) }
                        .frame(minHeight: KccSpacing.s12)
                } else {
                    Text(strings["title"]).font(.title2).accessibilityAddTraits(.isHeader)
                }
                SocialFlowSpacer()
                Button(action: coordinator.refresh) { Text(strings["refresh"]).frame(minHeight: 48) }
                    .frame(minHeight: KccSpacing.s12).disabled(coordinator.state.loading)
            }.padding(.horizontal, KccSpacing.s4)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                    if let failure = coordinator.state.failure {
                        Text(strings[failureKey(failure)])
                        Button(action: coordinator.refresh) { Text(strings["retry"]).frame(minHeight: 48) }.kccPrimaryAction()
                    }
                    if coordinator.state.selectedId == nil {
                        ForEach(coordinator.state.posts) { post in
                            VStack(alignment: .leading, spacing: KccSpacing.s2) {
                                Button { coordinator.open(post.id) } label: {
                                    DriverPostBody(post: post, strings: strings)
                                }.buttonStyle(.plain)
                                DriverContentSafetyActions(viewerUID: uid, authorUID: post.authorUid,
                                    target: PublicContentTarget(surface: "driver_post", itemID: post.id))
                            }.kccContentCard()
                        }
                        if !coordinator.state.loading && coordinator.state.failure == nil && coordinator.state.posts.isEmpty {
                            Text(strings["empty"])
                        }
                    } else if let post = coordinator.state.post {
                        VStack(alignment: .leading, spacing: KccSpacing.s2) {
                            DriverPostBody(post: post, strings: strings)
                            DriverContentSafetyActions(viewerUID: uid, authorUID: post.authorUid,
                                target: PublicContentTarget(surface: "driver_post", itemID: post.id))
                        }.kccContentCard()
                        Text(strings["comments"]).font(.headline).accessibilityAddTraits(.isHeader)
                        ForEach(coordinator.state.comments) { comment in
                            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                                Text(comment.author).font(.headline)
                                if let date = comment.createdAt {
                                    Text(date.formatted(date: .abbreviated, time: .shortened))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Text(comment.text).font(.body).fixedSize(horizontal: false, vertical: true)
                                DriverContentSafetyActions(viewerUID: uid, authorUID: comment.authorUid,
                                    target: PublicContentTarget(surface: "driver_comment", itemID: comment.id, threadID: post.id))
                            }.frame(maxWidth: .infinity, alignment: .leading).kccContentCard()
                        }
                        if !coordinator.state.loading && coordinator.state.comments.isEmpty { Text(strings["noComments"]) }
                    }
                    if coordinator.state.loading { ProgressView(strings["loading"]) }
                    if !coordinator.state.loading && coordinator.state.next != nil {
                        Button(action: coordinator.more) { Text(strings["more"]).frame(minHeight: 48) }.buttonStyle(.bordered)
                    }
                }.padding(KccSpacing.s4)
            }
        }
        .kccReadingSurface(pageBackground: true)
        .tint(KccTheme.driver)
        .task(id: DriverFeedTaskIdentity(threadId: coordinator.state.selectedId, active: isActive && scenePhase == .active)) {
            guard isActive && scenePhase == .active else { coordinator.pause(); return }
            guard let uid, let repository else { coordinator.refresh(); return }
            for await _ in repository.invalidations(uid: uid, threadId: coordinator.state.selectedId) {
                guard !Task.isCancelled else { return }
                coordinator.invalidate()
            }
        }
        .onDisappear { coordinator.pause() }
    }

    private func failureKey(_ failure: DriverFeedFailure) -> String {
        switch failure {
        case .error: "error"
        case .unavailable: "unavailable"
        case .onboarding: "onboarding"
        case .canonicalOnboarding: "canonicalOnboarding"
        case .restricted: "restricted"
        }
    }
}

private struct DriverFeedTaskIdentity: Equatable {
    let threadId: String?
    let active: Bool
}

struct DriverPostBody: View {
    let post: DriverPost
    let strings: DriverFeedStrings

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            SocialMemberLabel(name: post.author)
            // Source is inline metadata, never a separate banner, logo, or chat label.
            Text([post.createdAt?.formatted(date: .abbreviated, time: .shortened), strings["source"]]
                .compactMap { $0 }.joined(separator: " · "))
                .font(.caption).foregroundStyle(.secondary)
            Text(post.text).font(.body).fixedSize(horizontal: false, vertical: true)
            ForEach(Array(post.mediaURLs.enumerated()), id: \.offset) { _, url in
                AsyncImage(url: url) { phase in
                    if let image = phase.image { image.resizable().scaledToFit() }
                    else { Image(systemName: "photo").foregroundStyle(KccTheme.secondary).frame(minHeight: 80) }
                }
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
                .accessibilityLabel(strings["image"])
            }
            SocialFlow {
                Label("\(strings["likes"]): \(post.likes)", systemImage: "heart")
                Label("\(strings["comments"]): \(post.replies)", systemImage: "bubble.right")
            }
            .font(.caption).foregroundStyle(KccTheme.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
