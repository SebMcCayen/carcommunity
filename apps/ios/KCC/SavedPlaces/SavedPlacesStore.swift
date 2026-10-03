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
                [LossyDecodable<SavedPlace>].self,
                from: data
              )
        else { return [] }
        return SavedPlacesPolicy.normalize(decoded.compactMap(\.value))
    }

    func save(_ places: [SavedPlace]) {
        let normalized = SavedPlacesPolicy.normalize(places)
        guard let data = try? JSONEncoder().encode(normalized) else { return }
        defaults.set(data, forKey: key)
    }
}

/// Decodes one array element independently so a stale future kind or a single
/// malformed field cannot discard every valid saved place for the account.
private struct LossyDecodable<Value: Decodable>: Decodable {
    let value: Value?

    init(from decoder: Decoder) throws {
        value = try? Value(from: decoder)
    }
}
