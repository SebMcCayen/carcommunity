import Foundation

protocol AddressSearchClient: Sendable {
    func search(query: String, proximity: MapPoint?) async throws -> [PlaceSuggestion]
}

enum AddressSearchError: Error, Equatable, Sendable {
    case unavailable
    case invalidResponse
}

struct UnavailableAddressSearchClient: AddressSearchClient {
    func search(query _: String, proximity _: MapPoint?) async throws -> [PlaceSuggestion] {
        throw AddressSearchError.unavailable
    }
}

/// Search Box compatible forward geocoding over the public Mapbox token already
/// used by Mapbox Maps. A missing token never creates this client, preserving
/// config-less builds and preventing accidental tokenless network traffic.
struct MapboxAddressSearchClient: AddressSearchClient {
    static let maximumResponseBytes = 1_000_000

    private let token: String
    private let language: String
    private let session: URLSession

    init(token: String, language: String, session: URLSession = Self.ephemeralSession()) {
        self.token = token
        self.language = language
        self.session = session
    }

    func search(query: String, proximity: MapPoint?) async throws -> [PlaceSuggestion] {
        let query = SavedPlacesPolicy.normalizedQuery(query)
        guard !query.isEmpty, token.hasPrefix("pk."), let url = requestURL(query: query, proximity: proximity)
        else { return [] }
        var request = URLRequest(
            url: url,
            cachePolicy: .reloadIgnoringLocalCacheData,
            timeoutInterval: 15
        )
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw AddressSearchError.invalidResponse
        }
        guard http.expectedContentLength <= Int64(Self.maximumResponseBytes),
              data.count <= Self.maximumResponseBytes
        else { throw AddressSearchError.invalidResponse }
        return try Self.decode(data: data)
    }

    func requestURL(query: String, proximity: MapPoint?) -> URL? {
        var components = URLComponents(string: "https://api.mapbox.com/search/geocode/v6/forward")
        var items = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "access_token", value: token),
            URLQueryItem(name: "autocomplete", value: "true"),
            // Selected results are persisted as saved places. Mapbox requires
            // permanent geocoding rather than its default temporary mode.
            URLQueryItem(name: "permanent", value: "true"),
            URLQueryItem(name: "limit", value: String(SavedPlacesPolicy.maximumSearchResults)),
            URLQueryItem(name: "language", value: language)
        ]
        if let proximity, SavedPlacesPolicy.isValid(point: proximity) {
            items.append(URLQueryItem(
                name: "proximity",
                value: "\(proximity.longitude),\(proximity.latitude)"
            ))
        }
        components?.queryItems = items
        return components?.url
    }

    static func decode(data: Data) throws -> [PlaceSuggestion] {
        guard data.count <= maximumResponseBytes else { throw AddressSearchError.invalidResponse }
        let response = try JSONDecoder().decode(GeocodingResponse.self, from: data)
        var seen = Set<String>()
        var suggestions: [PlaceSuggestion] = []
        for feature in response.features {
            guard feature.geometry.coordinates.count >= 2 else { continue }
            let point = MapPoint(
                longitude: feature.geometry.coordinates[0],
                latitude: feature.geometry.coordinates[1]
            )
            guard SavedPlacesPolicy.isValid(point: point) else { continue }
            let properties = feature.properties
            let name = properties.name?.trimmingCharacters(in: .whitespacesAndNewlines)
                ?? properties.fullAddress?.trimmingCharacters(in: .whitespacesAndNewlines)
                ?? ""
            guard !name.isEmpty else { continue }
            let address = properties.fullAddress ?? properties.placeFormatted
            let rawID = properties.mapboxID ?? feature.id ?? "\(point.longitude),\(point.latitude)"
            let id = String(rawID.trimmingCharacters(in: .whitespacesAndNewlines).prefix(256))
            guard !id.isEmpty, seen.insert(id).inserted else { continue }
            suggestions.append(PlaceSuggestion(
                id: id,
                name: String(name.prefix(160)),
                address: address.map { String($0.prefix(240)) },
                point: point
            ))
            if suggestions.count == SavedPlacesPolicy.maximumSearchResults { break }
        }
        return suggestions
    }

    private static func ephemeralSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 15
        return URLSession(configuration: configuration)
    }
}

private struct GeocodingResponse: Decodable {
    let features: [GeocodingFeature]
}

private struct GeocodingFeature: Decodable {
    let id: String?
    let geometry: GeocodingGeometry
    let properties: GeocodingProperties
}

private struct GeocodingGeometry: Decodable {
    let coordinates: [Double]
}

private struct GeocodingProperties: Decodable {
    let mapboxID: String?
    let name: String?
    let fullAddress: String?
    let placeFormatted: String?

    enum CodingKeys: String, CodingKey {
        case mapboxID = "mapbox_id"
        case name
        case fullAddress = "full_address"
        case placeFormatted = "place_formatted"
    }
}
