import Foundation
import Observation

struct ChangelogEntry: Codable, Equatable, Identifiable, Sendable {
    let buildNumber: Int
    let versionName: String
    let releaseDate: String
    let highlightKeys: [String]
    let changeKeys: [String]

    var id: Int { buildNumber }
}

struct UpdateAnnouncement: Equatable, Identifiable, Sendable {
    let entry: ChangelogEntry
    let includesEarlierVersions: Bool

    var id: Int { entry.buildNumber }
}

private struct ChangelogDocument: Decodable {
    let entries: [ChangelogEntry]
}

enum Changelog {
    static let pageEntryLimit = 10
    static let popupHighlightLimit = 3

    static func parse(_ data: Data) -> [ChangelogEntry] {
        guard let decoded = try? JSONDecoder().decode(ChangelogDocument.self, from: data) else {
            return []
        }
        var seen = Set<Int>()
        let uniqueEntries = decoded.entries
            .filter {
                $0.buildNumber > 0
                    && !$0.versionName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    && !$0.releaseDate.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    && !$0.changeKeys.isEmpty
            }
            .filter { seen.insert($0.buildNumber).inserted }
        return uniqueEntries.sorted { $0.buildNumber > $1.buildNumber }
    }

    static func latestEntries(_ entries: [ChangelogEntry], limit: Int = pageEntryLimit) -> [ChangelogEntry] {
        guard limit > 0 else { return [] }
        return Array(entries.sorted { $0.buildNumber > $1.buildNumber }.prefix(limit))
    }

    static func announcement(
        entries: [ChangelogEntry],
        lastSeenBuild: Int?,
        currentBuild: Int
    ) -> UpdateAnnouncement? {
        guard let lastSeenBuild, lastSeenBuild < currentBuild else { return nil }
        let unseen = entries
            .filter { $0.buildNumber > lastSeenBuild && $0.buildNumber <= currentBuild }
            .sorted { $0.buildNumber > $1.buildNumber }
        guard let newest = unseen.first else { return nil }
        return UpdateAnnouncement(entry: newest, includesEarlierVersions: unseen.count > 1)
    }
}

protocol ChangelogLoading: Sendable {
    func load() -> [ChangelogEntry]
}

struct BundledChangelogLoader: ChangelogLoading, @unchecked Sendable {
    let bundle: Bundle

    init(bundle: Bundle = .main) {
        self.bundle = bundle
    }

    func load() -> [ChangelogEntry] {
        guard let url = bundle.url(forResource: "changelog", withExtension: "json"),
              let data = try? Data(contentsOf: url)
        else { return [] }
        return Changelog.parse(data)
    }
}

@MainActor
@Observable
final class WhatsNewCoordinator {
    private let loader: any ChangelogLoading
    private let defaults: UserDefaults
    private let currentBuild: Int
    private let lastSeenKey: String
    private(set) var entries: [ChangelogEntry] = []
    private(set) var announcement: UpdateAnnouncement?
    private var started = false

    init(
        loader: any ChangelogLoading = BundledChangelogLoader(),
        defaults: UserDefaults = .standard,
        currentBuild: Int = Bundle.main.kccBuildNumber,
        lastSeenKey: String = "whatsNew.lastSeenBuild"
    ) {
        self.loader = loader
        self.defaults = defaults
        self.currentBuild = currentBuild
        self.lastSeenKey = lastSeenKey
    }

    func start() {
        guard !started else { return }
        started = true
        entries = loader.load()
        guard defaults.object(forKey: lastSeenKey) != nil else {
            defaults.set(currentBuild, forKey: lastSeenKey)
            return
        }
        let lastSeen = defaults.integer(forKey: lastSeenKey)
        announcement = Changelog.announcement(
            entries: entries,
            lastSeenBuild: lastSeen,
            currentBuild: currentBuild
        )
        if announcement == nil, lastSeen < currentBuild {
            defaults.set(currentBuild, forKey: lastSeenKey)
        }
    }

    func acknowledge() {
        defaults.set(currentBuild, forKey: lastSeenKey)
        announcement = nil
    }
}

extension Bundle {
    var kccBuildNumber: Int {
        guard let raw = object(forInfoDictionaryKey: "CFBundleVersion") as? String,
              let value = Int(raw), value > 0 else { return 0 }
        return value
    }

    var kccMarketingVersion: String {
        (object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? ""
    }
}
