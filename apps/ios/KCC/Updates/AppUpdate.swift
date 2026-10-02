import Foundation
import Observation

struct AppUpdateAvailability: Equatable, Sendable {
    let identifier: String
    let version: String
    let storeURL: URL
    let isRequired: Bool
}

protocol AppUpdateSource: Sendable {
    func fetch() async -> AppUpdateAvailability?
}

struct AppUpdateDismissal: Codable, Equatable, Sendable {
    let identifier: String
    let dismissedAt: Date
}

enum AppUpdatePolicy {
    static let dismissalInterval: TimeInterval = 7 * 24 * 60 * 60

    static func shouldPresent(
        _ availability: AppUpdateAvailability?,
        dismissal: AppUpdateDismissal?,
        now: Date,
        isDriving: Bool,
        announcementIsPresented: Bool
    ) -> Bool {
        guard let availability, !isDriving, !announcementIsPresented else { return false }
        if availability.isRequired { return true }
        guard let dismissal, dismissal.identifier == availability.identifier else { return true }
        return now.timeIntervalSince(dismissal.dismissedAt) >= dismissalInterval
    }
}

struct AppStoreLookupSource: AppUpdateSource {
    private let bundleIdentifier: String?
    private let currentVersion: String
    private let countryCode: String?
    private let session: URLSession

    init(
        bundle: Bundle = .main,
        countryCode: String? = Locale.current.region?.identifier,
        session: URLSession = .shared
    ) {
        bundleIdentifier = bundle.bundleIdentifier
        currentVersion = bundle.kccMarketingVersion
        self.countryCode = countryCode
        self.session = session
    }

    init(bundleIdentifier: String?, currentVersion: String, countryCode: String?, session: URLSession) {
        self.bundleIdentifier = bundleIdentifier
        self.currentVersion = currentVersion
        self.countryCode = countryCode
        self.session = session
    }

    func fetch() async -> AppUpdateAvailability? {
        guard let bundleIdentifier,
              !bundleIdentifier.isEmpty,
              VersionNumber(currentVersion) != nil,
              var components = URLComponents(string: "https://itunes.apple.com/lookup")
        else { return nil }
        var query = [URLQueryItem(name: "bundleId", value: bundleIdentifier)]
        if let countryCode, countryCode.count == 2 {
            query.append(URLQueryItem(name: "country", value: countryCode.lowercased()))
        }
        components.queryItems = query
        guard let url = components.url else { return nil }

        do {
            let (data, response) = try await session.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let lookup = try? JSONDecoder().decode(LookupResponse.self, from: data),
                  let result = lookup.results.first(where: { $0.bundleId == bundleIdentifier }),
                  let offered = VersionNumber(result.version),
                  let installed = VersionNumber(currentVersion),
                  offered > installed,
                  let storeURL = Self.safeStoreURL(trackViewURL: result.trackViewUrl, trackID: result.trackId)
            else { return nil }
            return AppUpdateAvailability(
                identifier: result.version,
                version: result.version,
                storeURL: storeURL,
                isRequired: false
            )
        } catch {
            // Offline, missing listing, malformed response and cancellation all
            // degrade to no prompt. Updating must never block app startup.
            return nil
        }
    }

    static func safeStoreURL(trackViewURL: String?, trackID: Int?) -> URL? {
        if let trackID, trackID > 0,
           let native = URL(string: "itms-apps://itunes.apple.com/app/id\(trackID)") {
            return native
        }
        guard let trackViewURL,
              let url = URL(string: trackViewURL),
              url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(),
              host == "apps.apple.com" || host == "itunes.apple.com"
        else { return nil }
        return url
    }
}

private struct LookupResponse: Decodable {
    let results: [LookupResult]
}

private struct LookupResult: Decodable {
    let bundleId: String
    let version: String
    let trackViewUrl: String?
    let trackId: Int?
}

struct VersionNumber: Comparable, Sendable {
    let components: [Int]

    init?(_ raw: String) {
        let pieces = raw.split(separator: ".", omittingEmptySubsequences: false)
        guard !pieces.isEmpty,
              pieces.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) })
        else { return nil }
        components = pieces.map { Int($0)! }
    }

    static func < (lhs: VersionNumber, rhs: VersionNumber) -> Bool {
        let count = max(lhs.components.count, rhs.components.count)
        for index in 0..<count {
            let left = index < lhs.components.count ? lhs.components[index] : 0
            let right = index < rhs.components.count ? rhs.components[index] : 0
            if left != right { return left < right }
        }
        return false
    }
}

@MainActor
@Observable
final class AppUpdateCoordinator {
    private let source: any AppUpdateSource
    private let defaults: UserDefaults
    private let dismissalKey: String
    private let now: () -> Date
    private(set) var availability: AppUpdateAvailability?
    private var checked = false

    init(
        source: any AppUpdateSource = AppStoreLookupSource(),
        defaults: UserDefaults = .standard,
        dismissalKey: String = "appUpdate.dismissal",
        now: @escaping () -> Date = Date.init
    ) {
        self.source = source
        self.defaults = defaults
        self.dismissalKey = dismissalKey
        self.now = now
    }

    func checkOnce() async {
        guard !checked else { return }
        checked = true
        availability = await source.fetch()
    }

    func shouldPresent(isDriving: Bool, announcementIsPresented: Bool) -> Bool {
        AppUpdatePolicy.shouldPresent(
            availability,
            dismissal: dismissal,
            now: now(),
            isDriving: isDriving,
            announcementIsPresented: announcementIsPresented
        )
    }

    func dismiss() {
        guard let availability, !availability.isRequired else { return }
        let value = AppUpdateDismissal(identifier: availability.identifier, dismissedAt: now())
        if let data = try? JSONEncoder().encode(value) {
            defaults.set(data, forKey: dismissalKey)
        }
        self.availability = nil
    }

    func accepted() {
        availability = nil
    }

    private var dismissal: AppUpdateDismissal? {
        guard let data = defaults.data(forKey: dismissalKey) else { return nil }
        return try? JSONDecoder().decode(AppUpdateDismissal.self, from: data)
    }
}
