import CoreGraphics
import Foundation

enum DriveRouteThumbnail {
    static func decode(_ encoded: String?) -> [CGPoint] {
        guard let encoded, !encoded.isEmpty else { return [] }
        var index = encoded.startIndex
        var latitude = 0
        var longitude = 0
        var coordinates: [(Double, Double)] = []
        while index < encoded.endIndex {
            guard let latitudeDelta = component(encoded, index: &index),
                  let longitudeDelta = component(encoded, index: &index) else { return [] }
            latitude += latitudeDelta
            longitude += longitudeDelta
            let lat = Double(latitude) / 100_000
            let lon = Double(longitude) / 100_000
            guard (-90...90).contains(lat), (-180...180).contains(lon) else { return [] }
            coordinates.append((lat, lon))
        }
        guard coordinates.count >= 2 else { return [] }
        let minimumLatitude = coordinates.map(\.0).min()!
        let maximumLatitude = coordinates.map(\.0).max()!
        let minimumLongitude = coordinates.map(\.1).min()!
        let maximumLongitude = coordinates.map(\.1).max()!
        let longitudeScale = max(cos((minimumLatitude + maximumLatitude) / 2 * .pi / 180), 0.000_001)
        let xSpan = (maximumLongitude - minimumLongitude) * longitudeScale
        let ySpan = maximumLatitude - minimumLatitude
        guard max(xSpan, ySpan) * 111_320 >= 25 else { return [] }
        let scale = max(xSpan, ySpan)
        return coordinates.map { latitude, longitude in
            CGPoint(
                x: ((longitude - minimumLongitude) * longitudeScale / scale),
                y: ((maximumLatitude - latitude) / scale)
            )
        }
    }

    private static func component(_ encoded: String, index: inout String.Index) -> Int? {
        var result = 0
        var shift = 0
        while index < encoded.endIndex, shift < 30 {
            let scalar = encoded[index].asciiValue.map(Int.init) ?? -1
            encoded.formIndex(after: &index)
            guard scalar >= 63 else { return nil }
            let byte = scalar - 63
            result |= (byte & 0x1f) << shift
            if byte < 0x20 { return (result & 1) == 1 ? ~(result >> 1) : result >> 1 }
            shift += 5
        }
        return nil
    }
}
