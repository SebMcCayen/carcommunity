import Foundation
import Observation

@MainActor
@Observable
final class SavedPlacesCoordinator {
    private let store: SavedPlacesStore
    private let searchClient: AddressSearchClient
    @ObservationIgnored nonisolated(unsafe) private var searchTask: Task<Void, Never>?

    private(set) var places: [SavedPlace]
    private(set) var suggestions: [PlaceSuggestion] = []
    private(set) var isSearching = false
    private(set) var searchFailed = false
    var query = ""

    init(store: SavedPlacesStore, searchClient: AddressSearchClient) {
        self.store = store
        self.searchClient = searchClient
        places = store.load()
    }

    deinit { searchTask?.cancel() }

    func updateQuery(_ value: String, proximity: MapPoint?) {
        query = SavedPlacesPolicy.normalizedQuery(value)
        searchTask?.cancel()
        searchFailed = false
        guard query.count >= 2 else {
            suggestions = []
            isSearching = false
            return
        }
        let expectedQuery = query
        isSearching = true
        searchTask = Task { [weak self, searchClient] in
            do {
                try await Task.sleep(for: .milliseconds(300))
                let results = try await searchClient.search(query: expectedQuery, proximity: proximity)
                guard !Task.isCancelled, let self, self.query == expectedQuery else { return }
                self.suggestions = Array(
                    SavedPlacesPolicy.nearestFirst(results, from: proximity)
                        .prefix(SavedPlacesPolicy.maximumSearchResults)
                )
                self.isSearching = false
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, let self, self.query == expectedQuery else { return }
                self.suggestions = []
                self.isSearching = false
                self.searchFailed = true
            }
        }
    }

    func save(kind: SavedPlaceKind, place: PlaceSuggestion, label: String, replacingID: String? = nil) {
        guard let saved = SavedPlacesPolicy.create(kind: kind, place: place, label: label) else { return }
        var updated = places.filter {
            $0.id == saved.id || !SavedPlacesPolicy.refersToSamePlace($0.place, as: saved.place)
        }
        if let replacingID, replacingID != saved.id {
            updated = SavedPlacesPolicy.remove(id: replacingID, from: updated)
        }
        updated = SavedPlacesPolicy.upsert(saved, into: updated)
        places = SavedPlacesPolicy.normalize(updated)
        store.save(places)
    }

    func rename(_ place: SavedPlace, label: String) {
        save(kind: place.kind, place: place.place, label: label, replacingID: place.id)
    }

    func remove(id: String) {
        places = SavedPlacesPolicy.remove(id: id, from: places)
        store.save(places)
    }

    func clearSearch() {
        searchTask?.cancel()
        query = ""
        suggestions = []
        isSearching = false
        searchFailed = false
    }
}
