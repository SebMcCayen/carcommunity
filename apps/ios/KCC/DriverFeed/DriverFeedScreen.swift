import SwiftUI

/// The host owns the chat switch, localization, and opaque map cover.
struct DriverFeedScreen: View {
    let uid: String?
    let strings: DriverFeedStrings
    private let repository: (any DriverFeedRepository)?

    init(uid: String?, strings: DriverFeedStrings,
        repository: (any DriverFeedRepository)? = FirebaseDriverFeedRepository.createIfAvailable()) {
        self.uid = uid
        self.strings = strings
        self.repository = repository
    }

    var body: some View {
        DriverFeedSession(uid: uid, strings: strings, repository: repository).id(uid)
    }
}

private struct DriverFeedSession: View {
    let uid: String?
    let strings: DriverFeedStrings
    let repository: (any DriverFeedRepository)?
    @State private var coordinator: DriverFeedCoordinator
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme

    init(uid: String?, strings: DriverFeedStrings, repository: (any DriverFeedRepository)?) {
        self.uid = uid
        self.strings = strings
        self.repository = repository
        _coordinator = State(initialValue: DriverFeedCoordinator(repository: repository, uid: uid))
    }

    var body: some View {
        VStack(spacing: KccSpacing.s2) {
            HStack {
                if coordinator.state.selectedId != nil {
                    Button(action: coordinator.back) { Label(strings["back"], systemImage: "chevron.left") }
                        .frame(minHeight: KccSpacing.s12)
                } else {
                    Text(strings["title"]).font(.title2).accessibilityAddTraits(.isHeader)
                }
                Spacer()
                Button(strings["refresh"], action: coordinator.refresh)
                    .frame(minHeight: KccSpacing.s12).disabled(coordinator.state.loading)
            }.padding(.horizontal, KccSpacing.s4)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                    if let failure = coordinator.state.failure {
                        Text(strings[failureKey(failure)])
                        Button(strings["retry"], action: coordinator.refresh).frame(minHeight: KccSpacing.s12)
                    }
                    if coordinator.state.selectedId == nil {
                        ForEach(coordinator.state.posts) { post in
                            Button { coordinator.open(post.id) } label: {
                                DriverPostBody(post: post, strings: strings)
                                    .padding(KccSpacing.s4).frame(maxWidth: .infinity, alignment: .leading)
                                    .background(colorScheme == .dark ? KccDarkColors.surfaceBackground : KccLightColors.surfaceBackground,
                                        in: RoundedRectangle(cornerRadius: KccRadius.md))
                            }.buttonStyle(.plain)
                        }
                        if !coordinator.state.loading && coordinator.state.failure == nil && coordinator.state.posts.isEmpty {
                            Text(strings["empty"])
                        }
                    } else if let post = coordinator.state.post {
                        DriverPostBody(post: post, strings: strings)
                        Text(strings["comments"]).font(.headline).accessibilityAddTraits(.isHeader)
                        ForEach(coordinator.state.comments) { comment in
                            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                                Text(comment.author).font(.headline)
                                if let date = comment.createdAt {
                                    Text(date.formatted(date: .abbreviated, time: .shortened))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Text(comment.text).font(.body)
                            }
                        }
                        if !coordinator.state.loading && coordinator.state.comments.isEmpty { Text(strings["noComments"]) }
                    }
                    if coordinator.state.loading { ProgressView(strings["loading"]) }
                    if !coordinator.state.loading && coordinator.state.next != nil {
                        Button(strings["more"], action: coordinator.more).frame(minHeight: KccSpacing.s12)
                    }
                }.padding(KccSpacing.s4)
            }
        }
        .foregroundStyle(colorScheme == .dark ? KccDarkColors.textPrimary : KccLightColors.textPrimary)
        .tint(colorScheme == .dark ? KccDarkColors.driverActive : KccLightColors.driverActive)
        .background(colorScheme == .dark ? KccDarkColors.pageBackground : KccLightColors.pageBackground)
        .task(id: coordinator.state.selectedId) {
            guard let uid, let repository else { coordinator.refresh(); return }
            for await _ in repository.invalidations(uid: uid, threadId: coordinator.state.selectedId) {
                guard !Task.isCancelled else { return }
                coordinator.invalidate()
            }
        }
        .onChange(of: scenePhase) { _, phase in if phase == .active { coordinator.refresh() } }
        .onDisappear { coordinator.stop() }
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

private struct DriverPostBody: View {
    let post: DriverPost
    let strings: DriverFeedStrings

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text(post.author).font(.headline)
            // Source is inline metadata, never a separate banner, logo, or chat label.
            Text([post.createdAt?.formatted(date: .abbreviated, time: .shortened), strings["source"]]
                .compactMap { $0 }.joined(separator: " · "))
                .font(.caption).foregroundStyle(.secondary)
            Text(post.text).font(.body).fixedSize(horizontal: false, vertical: true)
            ForEach(Array(post.mediaURLs.enumerated()), id: \.offset) { _, url in
                AsyncImage(url: url) { image in image.resizable().scaledToFit() } placeholder: {
                    Image(systemName: "photo").foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity).accessibilityLabel(strings["image"])
            }
            Text("\(strings["likes"]): \(post.likes) · \(strings["comments"]): \(post.replies)")
                .font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
