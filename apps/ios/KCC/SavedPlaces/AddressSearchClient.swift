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
    private let token: String
    private let language: String
    private let session: URLSession

    init(token: String, language: String, session: URLSession = .shared) {
        self.token = token
        self.language = language
        self.session = session
    }

    func search(query: String, proximity: MapPoint?) async throws -> [PlaceSuggestion] {
        let query = SavedPlacesPolicy.normalizedQuery(query)
        guard !query.isEmpty, token.hasPrefix("pk."), let url = requestURL(query: query, proximity: proximity)
        else { return [] }
        let (data, response) = try await session.data(from: url)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw AddressSearchError.invalidResponse
        }
        return try Self.decode(data: data)
    }

    func requestURL(query: String, proximity: MapPoint?) -> URL? {
        var components = URLComponents(string: "https://api.mapbox.com/search/geocode/v6/forward")
        var items = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "access_token", value: token),
            URLQueryItem(name: "autocomplete", value: "true"),
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
        let response = try JSONDecoder().decode(GeocodingResponse.self, from: data)
        return response.features.prefix(SavedPlacesPolicy.maximumSearchResults).compactMap { feature in
            guard feature.geometry.coordinates.count >= 2 else { return nil }
            let point = MapPoint(
                longitude: feature.geometry.coordinates[0],
                latitude: feature.geometry.coordinates[1]
            )
            guard SavedPlacesPolicy.isValid(point: point) else { return nil }
            let properties = feature.properties
            let name = properties.name?.trimmingCharacters(in: .whitespacesAndNewlines)
                ?? properties.fullAddress?.trimmingCharacters(in: .whitespacesAndNewlines)
                ?? ""
            guard !name.isEmpty else { return nil }
            let address = properties.fullAddress ?? properties.placeFormatted
            return PlaceSuggestion(
                id: properties.mapboxID ?? feature.id ?? "\(point.longitude),\(point.latitude)",
                name: String(name.prefix(160)),
                address: address.map { String($0.prefix(240)) },
                point: point
            )
        }
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
