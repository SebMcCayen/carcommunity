import Foundation

/// Reads the public Mapbox runtime token embedded from the
/// `MAPBOX_ACCESS_TOKEN` build setting. Invalid or unresolved values are
/// treated as absent so config-less builds never instantiate Mapbox.
enum MapboxConfiguration {
    static func accessToken(
        infoDictionary: [String: Any]? = Bundle.main.infoDictionary
    ) -> String? {
        guard let rawValue = infoDictionary?["MBXAccessToken"] as? String else {
            return nil
        }

        let token = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard token.hasPrefix("pk."),
              !token.contains("$("),
              token.rangeOfCharacter(from: .whitespacesAndNewlines) == nil
        else {
            return nil
        }
        return token
    }
}
