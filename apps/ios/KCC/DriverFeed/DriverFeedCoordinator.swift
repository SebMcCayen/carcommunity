import Foundation
import Observation

struct DriverFeedState: Equatable {
    var posts: [DriverPost] = []
    var selectedId: String?
    var post: DriverPost?
    var comments: [DriverComment] = []
    var next: DriverCursor?
    var loading = false
    var failure: DriverFeedFailure?
}

@MainActor @Observable
final class DriverFeedCoordinator {
    private let repository: (any DriverFeedRepository)?
    private let uid: String?
    @ObservationIgnored nonisolated(unsafe) private var task: Task<Void, Never>?
    private var generation = 0
    private(set) var state = DriverFeedState()

    init(repository: (any DriverFeedRepository)?, uid: String?) {
        self.repository = repository
        self.uid = uid
    }

    deinit { task?.cancel() }
    func open(_ id: String) { reset(selected: id) }
    func back() { reset(selected: nil) }
    func refresh() { reset(selected: state.selectedId) }
    func invalidate() {
        task?.cancel()
        generation += 1
        state = DriverFeedState(selectedId: state.selectedId)
        load(more: false, debounce: true)
    }

    private func reset(selected: String?) {
        task?.cancel()
        generation += 1
        state = DriverFeedState(selectedId: selected)
        load(more: false)
    }

    func more() {
        guard !state.loading, state.next != nil else { return }
        load(more: true)
    }

    func stop() {
        pause()
        state = DriverFeedState()
    }

    func pause() {
        generation += 1
        task?.cancel()
        // Retain the navigation target, but reauthorize content when the host resumes.
        state = DriverFeedState(selectedId: state.selectedId)
    }

    private func load(more: Bool, debounce: Bool = false) {
        guard let repository, let uid else {
            state = DriverFeedState(failure: .restricted)
            return
        }
        let previous = state
        let ticket = generation
        state.loading = true
        state.failure = nil
        task = Task { [weak self] in
            do {
                // Coalesce access-listener bootstrap events; clear content immediately.
                if debounce { try await Task.sleep(for: .milliseconds(150)) }
                var next = previous
                if let id = previous.selectedId {
                    let detail = try await repository.detail(uid: uid, id: id, after: more ? previous.next : nil)
                    next.post = detail.post
                    let page = detail.comments
                    next.comments = Self.unique((more ? previous.comments : []) + page.items, id: \.id)
                    next.next = page.next
                } else {
                    let page = try await repository.feed(uid: uid, after: more ? previous.next : nil)
                    next.posts = Self.unique((more ? previous.posts : []) + page.items, id: \.id)
                    next.next = page.next
                }
                try Task.checkCancellation()
                next.loading = false
                if self?.generation == ticket { self?.state = next }
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, self?.generation == ticket else { return }
                self?.state = DriverFeedState(selectedId: previous.selectedId,
                    failure: (error as? DriverFeedFailure) ?? .error)
            }
        }
    }

    private static func unique<T>(_ values: [T], id: KeyPath<T, String>) -> [T] {
        var seen: Set<String> = []
        return values.filter { seen.insert($0[keyPath: id]).inserted }
    }
}
