import Foundation
import Observation
import SwiftUI

/// Read-only public affiliation, matching Android's ClubBadge projection.
struct LiveClubBadge: Equatable, Sendable {
    let clubID: String
    let clubName: String
    let logoURL: URL?

    var initials: String {
        String(clubName.split(whereSeparator: { $0.isWhitespace }).prefix(2)
            .compactMap(\.first).map(String.init).joined().uppercased().prefix(2))
    }

    var accessibilityLabel: String {
        String.localizedStringWithFormat(
            NSLocalizedString("clubBadge.accessibility", comment: "Club affiliation"), clubName)
    }

    static func decode(_ value: Any?) -> Self? {
        guard let id = publicClubAffiliationID(value),
              let fields = value as? [String: Any], let name = fields["clubName"] as? String
        else { return nil }
        return Self(clubID: id, clubName: name,
                    logoURL: trustedLogoURL(fields["logoUrl"] as? String, clubID: id))
    }

    /// Only Firebase HTTPS objects owned by this club, just like Android.
    private static func trustedLogoURL(_ value: String?, clubID: String) -> URL? {
        guard let value, value.utf16.count <= 2048,
              let url = URL(string: value), let parts = URLComponents(string: value),
              parts.scheme == "https", parts.host == "firebasestorage.googleapis.com",
              parts.user == nil, parts.password == nil, parts.port == nil, parts.fragment == nil,
              let path = parts.percentEncodedPath.removingPercentEncoding else { return nil }
        let segments = path.components(separatedBy: "/")
        guard segments.count == 9, segments[1] == "v0", segments[2] == "b",
              !segments[3].trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              segments[4] == "o", segments[5] == "clubLogos", segments[6] == clubID,
              segments[7].range(of: "\\A[A-Za-z0-9_-]{1,128}\\z", options: .regularExpression) != nil,
              segments[8].range(of: "\\A[A-Za-z0-9._-]{1,180}\\z", options: .regularExpression) != nil,
              segments[8] != ".", segments[8] != ".." else { return nil }
        return url
    }
}

@MainActor
protocol LiveClubBadgeRepository: AnyObject {
    /// Missing or unreadable profiles clear the badge.
    func badgeUpdates(uid: String) -> AsyncStream<LiveClubBadge?>
}

/// One public profile listener per actually drawn, already authorized marker.
/// This layer never discovers users or grants access to locations/private clubs.
@MainActor
@Observable
final class LiveClubBadgeCoordinator {
    static let maximumProfiles = 100
    private(set) var badges: [String: LiveClubBadge] = [:]
    private(set) var viewerID: String?
    @ObservationIgnored private let makeRepository: (String) -> (any LiveClubBadgeRepository)?
    @ObservationIgnored private var repository: (any LiveClubBadgeRepository)?
    @ObservationIgnored nonisolated(unsafe) private var tasks: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var tokens: [String: UUID] = [:]

    init(makeRepository: @escaping (String) -> (any LiveClubBadgeRepository)? = {
        FirebaseLiveClubBadgeRepository.createIfAvailable(viewerID: $0)
    }) {
        self.makeRepository = makeRepository
    }

    func badge(for uid: String, viewerID: String?) -> LiveClubBadge? {
        guard viewerID != nil, self.viewerID == viewerID else { return nil }
        return badges[uid]
    }

    func setRoster(viewerID: String?, drawnUIDs: Set<String>) {
        if self.viewerID != viewerID {
            stop()
            self.viewerID = viewerID
            repository = viewerID.flatMap(makeRepository)
        }
        guard viewerID != nil, let repository else { stop(); return }
        let wanted = Set(drawnUIDs.filter {
            !$0.isEmpty && $0.utf16.count <= 128 && !$0.contains("/")
        }.sorted().prefix(Self.maximumProfiles))
        for uid in Array(tasks.keys) where !wanted.contains(uid) {
            tokens.removeValue(forKey: uid)
            tasks.removeValue(forKey: uid)?.cancel()
            badges.removeValue(forKey: uid)
        }
        for uid in wanted where tasks[uid] == nil {
            let token = UUID()
            tokens[uid] = token
            tasks[uid] = Task { [weak self, repository] in
                for await badge in repository.badgeUpdates(uid: uid) {
                    guard !Task.isCancelled, let self, self.tokens[uid] == token else { return }
                    self.badges[uid] = badge
                }
                // A terminated/failed listener must not retain old cosmetics.
                guard !Task.isCancelled, let self, self.tokens[uid] == token else { return }
                self.badges.removeValue(forKey: uid)
            }
        }
    }

    func stop() {
        tokens.removeAll()
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
        badges.removeAll()
        repository = nil
        viewerID = nil
    }

    deinit { tasks.values.forEach { $0.cancel() } }
}

/// Kept outside the car's clip/rim so the bottom-centre logo stays visible.
struct LiveClubBadgeView: View {
    let badge: LiveClubBadge

    var body: some View {
        ZStack {
            Circle().fill(KccTheme.subtle)
            Text(badge.initials)
                .font(.system(size: 8, weight: .semibold))
                .foregroundStyle(KccTheme.text)
                .lineLimit(1)
                .accessibilityHidden(true)
            if let url = badge.logoURL {
                AsyncImage(url: url) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() }
                }
            }
        }
        .frame(width: 16, height: 16)
        .clipShape(Circle())
        .overlay(Circle().strokeBorder(KccTheme.overlay, lineWidth: 1))
        .accessibilityLabel(badge.accessibilityLabel)
        .accessibilityIdentifier("live.clubBadge")
    }
}

/// Shared nearby/convoy photo composition: affiliation sits above the clipped car.
struct LiveCarPhotoBadge: View {
    let imageURL: URL?
    let badge: LiveClubBadge?
    var diameter: CGFloat = 40

    var body: some View {
        ZStack {
            Circle().fill(KccTheme.driver)
            if let imageURL {
                AsyncImage(url: imageURL) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "car.side.fill").foregroundStyle(KccTheme.onDriver)
                }
            } else {
                Image(systemName: "car.side.fill").foregroundStyle(KccTheme.onDriver)
            }
        }
        .font(.system(size: 18, weight: .semibold))
        .frame(width: diameter, height: diameter)
        .clipShape(Circle())
        .overlay(Circle().stroke(KccTheme.driver, lineWidth: 2))
        .overlay(alignment: .bottom) {
            if let badge {
                LiveClubBadgeView(badge: badge).offset(y: 2).accessibilityHidden(true)
            }
        }
    }
}
