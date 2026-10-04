import Foundation

enum SavedPlaceKind: String, Codable, CaseIterable, Sendable {
    case home
    case work
    case favourite

    var titleKey: String {
        switch self {
        case .home: "addressSearch.savedHome"
        case .work: "addressSearch.savedWork"
        case .favourite: "addressSearch.savedFavourite"
        }
    }

    var systemImage: String {
        switch self {
        case .home: "house.fill"
        case .work: "briefcase.fill"
        case .favourite: "star.fill"
        }
    }
}

struct PlaceSuggestion: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let name: String
    let address: String?
    let point: MapPoint

    var secondaryText: String? {
        guard let address, !address.isEmpty, address != name else { return nil }
        return address
    }
}

struct SavedPlace: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let kind: SavedPlaceKind
    let label: String
    let place: PlaceSuggestion

    var displayLabel: String {
        switch kind {
        case .home, .work: String(localized: String.LocalizationValue(kind.titleKey))
        case .favourite: label
        }
    }
}

enum SavedPlacesPolicy {
    static let maximumCount = 6
    static let maximumLabelLength = 40
    static let maximumQueryLength = 120
    static let maximumSearchResults = 6

    static func create(
        kind: SavedPlaceKind,
        place: PlaceSuggestion,
        label: String
    ) -> SavedPlace? {
        guard isValid(point: place.point) else { return nil }
        let normalizedLabel = normalize(label: label).nilIfEmpty ?? normalize(label: place.name)
        guard !normalizedLabel.isEmpty else { return nil }
        let normalizedPlace = PlaceSuggestion(
            id: bounded(place.id.trimmingCharacters(in: .whitespacesAndNewlines), to: 256),
            name: bounded(place.name.trimmingCharacters(in: .whitespacesAndNewlines), to: 160),
            address: place.address.map {
                bounded($0.trimmingCharacters(in: .whitespacesAndNewlines), to: 240)
            }?.nilIfEmpty,
            point: place.point
        )
        return SavedPlace(
            id: id(for: kind, place: normalizedPlace),
            kind: kind,
            label: normalizedLabel,
            place: normalizedPlace
        )
    }

    static func upsert(_ saved: SavedPlace, into existing: [SavedPlace]) -> [SavedPlace] {
        var items = normalize(existing)
        if let index = items.firstIndex(where: { $0.id == saved.id }) {
            items[index] = saved
            return sorted(items)
        }
        if items.count >= maximumCount {
            guard let oldestFavourite = items.firstIndex(where: { $0.kind == .favourite }) else {
                return items
            }
            items.remove(at: oldestFavourite)
        }
        items.append(saved)
        return sorted(items)
    }

    static func remove(id: String, from existing: [SavedPlace]) -> [SavedPlace] {
        existing.filter { $0.id != id }
    }

    static func refersToSamePlace(_ candidate: PlaceSuggestion, as target: PlaceSuggestion) -> Bool {
        let candidateID = candidate.id.trimmingCharacters(in: .whitespacesAndNewlines)
        let targetID = target.id.trimmingCharacters(in: .whitespacesAndNewlines)
        if !candidateID.isEmpty, !targetID.isEmpty {
            return candidateID == targetID
        }
        return candidate.point == target.point
    }

    static func existingPlace(
        matching place: PlaceSuggestion,
        in savedPlaces: [SavedPlace]
    ) -> SavedPlace? {
        savedPlaces.first { refersToSamePlace($0.place, as: place) }
    }

    /// Distance ranks only when a real device fix is available. Equal-distance
    /// candidates retain Mapbox's relevance order.
    static func nearestFirst(
        _ results: [PlaceSuggestion],
        from origin: MapPoint?
    ) -> [PlaceSuggestion] {
        guard let origin, isValid(point: origin) else { return results }
        return results.enumerated().sorted { first, second in
            let firstDistance = distanceMeters(from: origin, to: first.element.point)
            let secondDistance = distanceMeters(from: origin, to: second.element.point)
            if firstDistance == secondDistance { return first.offset < second.offset }
            return firstDistance < secondDistance
        }.map(\.element)
    }

    static func normalize(_ raw: [SavedPlace]) -> [SavedPlace] {
        var seen = Set<String>()
        var valid: [SavedPlace] = []
        for candidate in raw {
            guard let rebuilt = create(
                kind: candidate.kind,
                place: candidate.place,
                label: candidate.label
            ), seen.insert(rebuilt.id).inserted else { continue }
            valid.append(rebuilt)
        }
        var result = sorted(valid)
        while result.count > maximumCount,
              let oldestFavourite = result.firstIndex(where: { $0.kind == .favourite }) {
            result.remove(at: oldestFavourite)
        }
        return Array(result.prefix(maximumCount))
    }

    static func id(for kind: SavedPlaceKind, place: PlaceSuggestion) -> String {
        switch kind {
        case .home: return "home"
        case .work: return "work"
        case .favourite:
            if !place.id.isEmpty { return "fav:\(place.id)" }
            return String(format: "fav:%.6f,%.6f", place.point.longitude, place.point.latitude)
        }
    }

    static func isValid(point: MapPoint) -> Bool {
        point.latitude.isFinite && point.longitude.isFinite
            && (-90...90).contains(point.latitude)
            && (-180...180).contains(point.longitude)
    }

    static func normalizedQuery(_ query: String) -> String {
        bounded(query.trimmingCharacters(in: .whitespacesAndNewlines), to: maximumQueryLength)
    }

    private static func normalize(label: String) -> String {
        bounded(label.trimmingCharacters(in: .whitespacesAndNewlines), to: maximumLabelLength)
    }

    static func bounded(_ value: String, to maximumScalars: Int) -> String {
        String(value.unicodeScalars.prefix(maximumScalars))
    }

    private static func distanceMeters(from first: MapPoint, to second: MapPoint) -> Double {
        let latitude1 = first.latitude * .pi / 180
        let latitude2 = second.latitude * .pi / 180
        let latitudeDelta = latitude2 - latitude1
        let longitudeDelta = (second.longitude - first.longitude) * .pi / 180
        let haversine = pow(sin(latitudeDelta / 2), 2)
            + cos(latitude1) * cos(latitude2) * pow(sin(longitudeDelta / 2), 2)
        return 2 * 6_371_000 * asin(min(1, sqrt(haversine)))
    }

    private static func sorted(_ items: [SavedPlace]) -> [SavedPlace] {
        let home = items.filter { $0.kind == .home }
        let work = items.filter { $0.kind == .work }
        let favourites = items.filter { $0.kind == .favourite }
        return home + work + favourites
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
