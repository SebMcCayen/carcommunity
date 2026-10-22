import Foundation
import Observation

enum BillboardsUiState: Equatable, Sendable {
    case unavailable
    case loading
    case loaded
    case failed(code: String?)
}

@MainActor
@Observable
final class BillboardsCoordinator {
    private let repository: BillboardsRepository?
    private let now: @Sendable () -> Date
    @ObservationIgnored nonisolated(unsafe) private var subscription: Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) private var boundaryTask: Task<Void, Never>?
    private var loadedBillboards: [Billboard] = []

    private(set) var state: BillboardsUiState
    private(set) var visibleBillboards: [Billboard] = []
    private(set) var selectedBillboardId: String?

    var markers: [MapBillboardMarker] {
        visibleBillboards.map {
            MapBillboardMarker(id: $0.id, longitude: $0.longitude, latitude: $0.latitude)
        }
    }

    var selectedBillboard: Billboard? {
        guard let selectedBillboardId else { return nil }
        return visibleBillboards.first { $0.id == selectedBillboardId }
    }

    init(repository: BillboardsRepository?, now: @escaping @Sendable () -> Date = Date.init) {
        self.repository = repository
        self.now = now
        self.state = repository == nil ? .unavailable : .loading
    }

    deinit {
        subscription?.cancel()
        boundaryTask?.cancel()
    }

    func start() {
        guard subscription == nil, let repository else { return }
        let stream = repository.activeBillboards()
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(snapshot)
            }
        }
    }

    func stop() {
        subscription?.cancel()
        subscription = nil
        boundaryTask?.cancel()
        boundaryTask = nil
        loadedBillboards = []
        visibleBillboards = []
        selectedBillboardId = nil
        state = repository == nil ? .unavailable : .loading
    }

    func open(billboardId: String) {
        guard visibleBillboards.contains(where: { $0.id == billboardId }) else { return }
        selectedBillboardId = billboardId
        record(billboardId: billboardId, type: .open)
    }

    func dismiss() {
        selectedBillboardId = nil
    }

    func recordAction(_ action: BillboardAction, for billboard: Billboard) {
        record(billboardId: billboard.id, type: action.interactionType)
    }

    /// Exposed internally so schedule boundaries stay deterministic in tests.
    func refreshVisibility(at date: Date) {
        visibleBillboards = BillboardVisibility.visible(loadedBillboards, at: date)
        if selectedBillboard == nil { selectedBillboardId = nil }
        scheduleNextBoundary(after: date)
    }

    private func apply(_ snapshot: BillboardsSnapshot) {
        switch snapshot {
        case .failed(let code):
            // A permission or listener failure must fail closed. Retaining the
            // previous snapshot could keep an advert visible after access was
            // revoked or its server-owned mapVisible state changed.
            boundaryTask?.cancel()
            boundaryTask = nil
            loadedBillboards = []
            visibleBillboards = []
            selectedBillboardId = nil
            state = .failed(code: code)
        case .loaded(let billboards):
            state = .loaded
            loadedBillboards = billboards
            refreshVisibility(at: now())
        }
    }

    private func scheduleNextBoundary(after date: Date) {
        boundaryTask?.cancel()
        guard let boundary = BillboardVisibility.nextBoundary(after: date, in: loadedBillboards)
        else {
            boundaryTask = nil
            return
        }
        let delay = max(boundary.timeIntervalSince(date), 0.001)
        boundaryTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(delay)) } catch { return }
            guard !Task.isCancelled, let self else { return }
            self.refreshVisibility(at: self.now())
        }
    }

    private func record(billboardId: String, type: BillboardInteractionType) {
        guard let repository else { return }
        Task { try? await repository.recordInteraction(billboardId: billboardId, type: type) }
    }
}
