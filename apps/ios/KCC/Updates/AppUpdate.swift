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
    static let maximumResponseBytes = 128 * 1_024
    static let maximumResultCount = 50

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
        if let countryCode,
           countryCode.unicodeScalars.count == 2,
           countryCode.unicodeScalars.allSatisfy({
               (65...90).contains($0.value) || (97...122).contains($0.value)
           }) {
            query.append(URLQueryItem(name: "country", value: countryCode.lowercased()))
        }
        components.queryItems = query
        guard let url = components.url else { return nil }

        do {
            var request = URLRequest(url: url)
            request.cachePolicy = .reloadIgnoringLocalCacheData
            request.timeoutInterval = 15
            let (bytes, response) = try await session.bytes(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  response.expectedContentLength <= Int64(Self.maximumResponseBytes)
                      || response.expectedContentLength == NSURLSessionTransferSizeUnknown
            else { return nil }
            var data = Data()
            data.reserveCapacity(min(
                max(Int(response.expectedContentLength), 0),
                Self.maximumResponseBytes
            ))
            for try await byte in bytes {
                guard data.count < Self.maximumResponseBytes else { return nil }
                data.append(byte)
            }
            guard
                  let availability = Self.availability(
                      from: data,
                      bundleIdentifier: bundleIdentifier,
                      currentVersion: currentVersion
                  )
            else { return nil }
            return availability
        } catch {
            // Offline, missing listing, malformed response and cancellation all
            // degrade to no prompt. Updating must never block app startup.
            return nil
        }
    }

    static func availability(
        from data: Data,
        bundleIdentifier: String,
        currentVersion: String
    ) -> AppUpdateAvailability? {
        guard data.count <= maximumResponseBytes,
              let lookup = try? JSONDecoder().decode(LookupResponse.self, from: data),
              lookup.results.count <= maximumResultCount,
              let result = lookup.results.first(where: { $0.bundleId == bundleIdentifier }),
              result.bundleId.utf8.count <= 255,
              result.version.utf8.count <= 100,
              let offered = VersionNumber(result.version),
              let installed = VersionNumber(currentVersion),
              offered > installed,
              let storeURL = safeStoreURL(trackViewURL: result.trackViewUrl, trackID: result.trackId)
        else { return nil }
        return AppUpdateAvailability(
            identifier: result.version,
            version: result.version,
            storeURL: storeURL,
            isRequired: false
        )
    }

    static func safeStoreURL(trackViewURL: String?, trackID: Int?) -> URL? {
        if let trackID, trackID > 0,
           let native = URL(string: "itms-apps://itunes.apple.com/app/id\(trackID)") {
            return native
        }
        guard let trackViewURL,
              trackViewURL.utf8.count <= 2_048,
              let url = URL(string: trackViewURL),
              url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(),
              host == "apps.apple.com" || host == "itunes.apple.com",
              url.user == nil,
              url.password == nil,
              url.port == nil
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
        guard !pieces.isEmpty, pieces.count <= 16, raw.utf8.count <= 100 else { return nil }
        var parsed: [Int] = []
        parsed.reserveCapacity(pieces.count)
        for piece in pieces {
            guard !piece.isEmpty,
                  piece.unicodeScalars.allSatisfy({ (48...57).contains($0.value) }),
                  let value = Int(piece)
            else { return nil }
            parsed.append(value)
        }
        components = parsed
    }

    static func == (lhs: VersionNumber, rhs: VersionNumber) -> Bool {
        compare(lhs, rhs) == 0
    }

    static func < (lhs: VersionNumber, rhs: VersionNumber) -> Bool {
        compare(lhs, rhs) < 0
    }

    private static func compare(_ lhs: VersionNumber, _ rhs: VersionNumber) -> Int {
        let count = max(lhs.components.count, rhs.components.count)
        for index in 0..<count {
            let left = index < lhs.components.count ? lhs.components[index] : 0
            let right = index < rhs.components.count ? rhs.components[index] : 0
            if left != right { return left < right ? -1 : 1 }
        }
        return 0
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

    /// Required offers stay active after App Store handoff. Recheck them when
    /// the app returns so a policy source can lift or replace the gate.
    func recheckRequiredUpdate() async {
        guard availability?.isRequired == true else { return }
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
        if availability?.isRequired != true {
            availability = nil
        }
    }

    private var dismissal: AppUpdateDismissal? {
        guard let data = defaults.data(forKey: dismissalKey) else { return nil }
        return try? JSONDecoder().decode(AppUpdateDismissal.self, from: data)
    }
}
