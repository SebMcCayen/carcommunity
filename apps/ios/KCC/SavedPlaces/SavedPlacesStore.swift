import Foundation

@MainActor
protocol SavedPlacesStore: AnyObject {
    func load() -> [SavedPlace]
    func save(_ places: [SavedPlace])
}

/// Device-local persistence with a distinct key for every authenticated uid.
/// Home and work addresses never enter a shared or signed-out namespace.
@MainActor
final class UserDefaultsSavedPlacesStore: SavedPlacesStore {
    private let defaults: UserDefaults
    private let key: String

    init(uid: String, defaults: UserDefaults = .standard) {
        precondition(!uid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.defaults = defaults
        let accountKey = Data(uid.utf8).base64EncodedString()
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "=", with: "")
        key = "ios.savedPlaces.v1.\(accountKey)"
    }

    func load() -> [SavedPlace] {
        guard let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode(
                [LossyDecodable<PersistedSavedPlace>].self,
                from: data
              )
        else { return [] }
        return SavedPlacesPolicy.normalize(decoded.compactMap { $0.value?.savedPlace })
    }

    func save(_ places: [SavedPlace]) {
        let normalized = SavedPlacesPolicy.normalize(places)
        guard let data = try? JSONEncoder().encode(normalized) else { return }
        defaults.set(data, forKey: key)
    }
}

/// The on-device wire shape is intentionally more tolerant than the runtime
/// model. Android preserves an otherwise valid place from a newer build by
/// degrading an absent or unknown shortcut kind to Favourite; iOS must do the
/// same so a future enum case cannot erase a member's saved location.
private struct PersistedSavedPlace: Decodable {
    let kind: SavedPlaceKind
    let label: String
    let place: PlaceSuggestion

    private enum CodingKeys: String, CodingKey {
        case kind
        case label
        case place
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        let rawKind = try values.decodeIfPresent(String.self, forKey: .kind)
        kind = rawKind.flatMap(SavedPlaceKind.init(rawValue:)) ?? .favourite
        label = try values.decode(String.self, forKey: .label)
        place = try values.decode(PlaceSuggestion.self, forKey: .place)
    }

    var savedPlace: SavedPlace? {
        SavedPlacesPolicy.create(kind: kind, place: place, label: label)
    }
}

/// Decodes one array element independently so a single malformed field cannot
/// discard every valid saved place for the account.
private struct LossyDecodable<Value: Decodable>: Decodable {
    let value: Value?

    init(from decoder: Decoder) throws {
        value = try? Value(from: decoder)
    }
}
