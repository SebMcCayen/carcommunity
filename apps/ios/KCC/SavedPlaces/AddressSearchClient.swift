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

/// One-off text search through Mapbox Search Box `/forward`, which returns POIs
/// and businesses as well as addresses. It uses the public Mapbox token already
/// used by Mapbox Maps. A missing token never creates this client, preserving
/// config-less builds and preventing accidental tokenless network traffic.
struct MapboxAddressSearchClient: AddressSearchClient {
    static let maximumResponseBytes = 1_000_000
    static let fallbackProximity = MapPoint(longitude: 12.0730, latitude: 57.4874)

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
        let query = SavedPlacesPolicy.normalizedQuery(query)
        guard !query.isEmpty, token.hasPrefix("pk.") else { return nil }
        var components = URLComponents(string: "https://api.mapbox.com/search/searchbox/v1/forward")
        var items = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "access_token", value: token),
            URLQueryItem(name: "limit", value: String(SavedPlacesPolicy.maximumSearchResults))
        ]
        let language = SavedPlacesPolicy.bounded(
            language.trimmingCharacters(in: .whitespacesAndNewlines),
            to: 35
        )
        if !language.isEmpty {
            items.append(URLQueryItem(name: "language", value: language))
        }
        let liveProximity = proximity.flatMap {
            SavedPlacesPolicy.isValid(point: $0) ? $0 : nil
        }
        let bias = liveProximity ?? Self.fallbackProximity
        items.append(URLQueryItem(
            name: "proximity",
            value: "\(bias.longitude),\(bias.latitude)"
        ))
        if liveProximity == nil {
            items.append(URLQueryItem(name: "country", value: "SE"))
        }
        components?.queryItems = items
        return components?.url
    }

    static func decode(data: Data) throws -> [PlaceSuggestion] {
        guard data.count <= maximumResponseBytes else { throw AddressSearchError.invalidResponse }
        let response = try JSONDecoder().decode(SearchBoxResponse.self, from: data)
        var seen = Set<String>()
        var suggestions: [PlaceSuggestion] = []
        for feature in response.features.compactMap(\.value) {
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
            let id = SavedPlacesPolicy.bounded(
                rawID.trimmingCharacters(in: .whitespacesAndNewlines),
                to: 256
            )
            guard !id.isEmpty, seen.insert(id).inserted else { continue }
            suggestions.append(PlaceSuggestion(
                id: id,
                name: SavedPlacesPolicy.bounded(name, to: 160),
                address: address.map { SavedPlacesPolicy.bounded($0, to: 240) },
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

private struct SearchBoxResponse: Decodable {
    let features: [LossySearchFeature]
}

/// One malformed or future feature must not discard valid search results from
/// the same response.
private struct LossySearchFeature: Decodable {
    let value: SearchBoxFeature?

    init(from decoder: Decoder) throws {
        value = try? SearchBoxFeature(from: decoder)
    }
}

private struct SearchBoxFeature: Decodable {
    let id: String?
    let geometry: SearchBoxGeometry
    let properties: SearchBoxProperties
}

private struct SearchBoxGeometry: Decodable {
    let coordinates: [Double]
}

private struct SearchBoxProperties: Decodable {
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
