import Foundation

@MainActor
protocol SavedPlacesStore: AnyObject {
    func load() -> [SavedPlace]
    @discardableResult func save(_ places: [SavedPlace]) -> Bool
    @discardableResult func clear() -> Bool
}

/// Account-scoped device-local persistence for precise saved locations.
///
/// Files use complete data protection, stay out of device backups, and live in
/// the app's private Application Support container. The legacy UserDefaults
/// payload is migrated only after the protected write succeeds.
@MainActor
final class ProtectedFileSavedPlacesStore: SavedPlacesStore {
    private let defaults: UserDefaults
    private let fileManager: FileManager
    private let legacyKey: String
    private let directoryURL: URL?
    let storageURL: URL?

    init(
        uid: String,
        defaults: UserDefaults = .standard,
        fileManager: FileManager = .default,
        storageDirectory: URL? = nil
    ) {
        let trimmedUid = uid.trimmingCharacters(in: .whitespacesAndNewlines)
        precondition(!trimmedUid.isEmpty)
        self.defaults = defaults
        self.fileManager = fileManager

        let accountKey = Data(trimmedUid.utf8).base64EncodedString()
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "=", with: "")
        legacyKey = "ios.savedPlaces.v1.\(accountKey)"

        let directory = storageDirectory ?? fileManager.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first?.appendingPathComponent("SavedPlaces", isDirectory: true)
        directoryURL = directory
        storageURL = directory?.appendingPathComponent("\(accountKey).json", isDirectory: false)
    }

    func load() -> [SavedPlace] {
        if let storageURL, fileManager.fileExists(atPath: storageURL.path) {
            guard let data = try? Data(contentsOf: storageURL) else { return [] }
            let decoded = decode(data)
            // Complete a migration interrupted after its atomic file write.
            defaults.removeObject(forKey: legacyKey)
            return decoded
        }

        guard let legacyData = defaults.data(forKey: legacyKey) else { return [] }
        let migrated = decode(legacyData)
        guard !migrated.isEmpty else {
            defaults.removeObject(forKey: legacyKey)
            return []
        }
        guard persist(migrated) else { return migrated }
        defaults.removeObject(forKey: legacyKey)
        return migrated
    }

    @discardableResult
    func save(_ places: [SavedPlace]) -> Bool {
        persist(SavedPlacesPolicy.normalize(places))
    }

    /// Removes both current protected storage and any not-yet-migrated legacy
    /// payload. Call this only for an explicit, successfully ended session.
    @discardableResult
    func clear() -> Bool {
        defaults.removeObject(forKey: legacyKey)
        guard let storageURL else { return defaults.object(forKey: legacyKey) == nil }
        do {
            if fileManager.fileExists(atPath: storageURL.path) {
                try fileManager.removeItem(at: storageURL)
            }
            return defaults.object(forKey: legacyKey) == nil
                && !fileManager.fileExists(atPath: storageURL.path)
        } catch {
            return false
        }
    }

    private func persist(_ places: [SavedPlace]) -> Bool {
        guard let directoryURL, let storageURL,
              let data = try? JSONEncoder().encode(places),
              prepareDirectory(directoryURL)
        else { return false }

        let stagedURL = directoryURL.appendingPathComponent(
            ".\(UUID().uuidString).tmp",
            isDirectory: false
        )
        do {
            try data.write(to: stagedURL, options: [.atomic, .completeFileProtection])
            try fileManager.setAttributes(
                [.protectionKey: FileProtectionType.complete],
                ofItemAtPath: stagedURL.path
            )
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var mutableURL = stagedURL
            try mutableURL.setResourceValues(values)
            if fileManager.fileExists(atPath: storageURL.path) {
                _ = try fileManager.replaceItemAt(
                    storageURL,
                    withItemAt: stagedURL,
                    backupItemName: nil,
                    options: .usingNewMetadataOnly
                )
            } else {
                try fileManager.moveItem(at: stagedURL, to: storageURL)
            }
            return true
        } catch {
            try? fileManager.removeItem(at: stagedURL)
            return false
        }
    }

    private func prepareDirectory(_ directoryURL: URL) -> Bool {
        do {
            try fileManager.createDirectory(
                at: directoryURL,
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete]
            )
            try fileManager.setAttributes(
                [.protectionKey: FileProtectionType.complete],
                ofItemAtPath: directoryURL.path
            )
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var mutableURL = directoryURL
            try mutableURL.setResourceValues(values)
            return true
        } catch {
            return false
        }
    }

    private func decode(_ data: Data) -> [SavedPlace] {
        guard let decoded = try? JSONDecoder().decode(
            [LossyDecodable<PersistedSavedPlace>].self,
            from: data
        ) else { return [] }
        return SavedPlacesPolicy.normalize(decoded.compactMap { $0.value?.savedPlace })
    }
}

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
