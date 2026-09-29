import Foundation

/// Default viewport discovery radius, matching Android and the backend clamp.
let defaultNearbyLiveRadiusMeters = 15_000.0

/// Maximum simultaneous per-uid RTDB listeners used by the public map layer.
let maximumNearbyLiveMarkers = 50

/// One authorized discovery seed returned by `live-listNearby`.
struct NearbyLiveSession: Equatable, Sendable {
    let uid: String
    let latitude: Double
    let longitude: Double
    let displayName: String?
}

/// Defensive SDK-to-domain parser for `{ sessions: [...] }`.
enum NearbyLiveParser {
    static func parse(_ data: Any?) -> [NearbyLiveSession] {
        guard let root = data as? [String: Any],
              let sessions = root["sessions"] as? [Any]
        else { return [] }
        return sessions.compactMap(parseSession)
    }

    private static func parseSession(_ raw: Any) -> NearbyLiveSession? {
        guard let row = raw as? [String: Any],
              let uid = clean(row["uid"] as? String),
              let latitude = (row["latitude"] as? NSNumber)?.doubleValue,
              let longitude = (row["longitude"] as? NSNumber)?.doubleValue,
              latitude.isFinite, longitude.isFinite,
              (-90...90).contains(latitude), (-180...180).contains(longitude)
        else { return nil }
        return NearbyLiveSession(
            uid: uid,
            latitude: latitude,
            longitude: longitude,
            displayName: clean(row["displayName"] as? String)
        )
    }

    private static func clean(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty else { return nil }
        return value
    }
}

/// Pure visibility rules shared by the coordinator and its tests.
enum NearbyLivePresentation {
    /// A marker older than the three-minute stationary heartbeat plus grace is hidden.
    static let staleAfter: TimeInterval = 4 * 60

    static func markers(
        orderedUids: [String],
        positions: [String: LiveMarker],
        currentUid: String?,
        excludedUids: Set<String>,
        now: Date = Date()
    ) -> [LiveMarker] {
        var seen = Set<String>()
        return orderedUids.compactMap { uid in
            guard seen.insert(uid).inserted,
                  !uid.isEmpty,
                  uid != currentUid,
                  !excludedUids.contains(uid),
                  let marker = positions[uid],
                  marker.uid == uid,
                  marker.latitude.isFinite, marker.longitude.isFinite,
                  (-90...90).contains(marker.latitude),
                  (-180...180).contains(marker.longitude),
                  marker.recordedAt.map({ now.timeIntervalSince($0) <= staleAfter }) ?? true
            else { return nil }
            return marker
        }
    }
}
