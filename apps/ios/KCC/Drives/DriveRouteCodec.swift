import Foundation

/// Byte-compatible with Android/backend's raw `CCRB` v1 route format. Raw
/// octet-stream is permitted by Storage rules, avoiding a platform-specific
/// gzip dependency while remaining readable by every existing decoder.
enum DriveRouteCodec {
    static func encode(_ points: [RecordedDrivePoint]) -> Data {
        var data = Data([0x43, 0x43, 0x52, 0x42, 0x01, 0x00])
        appendUnsigned(UInt64(points.count), to: &data)
        var previousLatitude = Int32(0)
        var previousLongitude = Int32(0)
        var previousTimestamp = Int64(0)
        for point in points {
            let latitude = Int32((point.latitude * 100_000).rounded())
            let longitude = Int32((point.longitude * 100_000).rounded())
            appendUnsigned(zigZag(latitude &- previousLatitude), to: &data)
            appendUnsigned(zigZag(longitude &- previousLongitude), to: &data)
            appendUnsigned(UInt64(max(0, point.timestampMilliseconds - previousTimestamp)), to: &data)
            previousLatitude = latitude
            previousLongitude = longitude
            previousTimestamp = point.timestampMilliseconds
        }
        return data
    }

    private static func zigZag(_ value: Int32) -> UInt64 {
        UInt64(UInt32(bitPattern: (value &<< 1) ^ (value >> 31)))
    }

    private static func appendUnsigned(_ value: UInt64, to data: inout Data) {
        var value = value
        while value & ~UInt64(0x7f) != 0 {
            data.append(UInt8((value & 0x7f) | 0x80))
            value >>= 7
        }
        data.append(UInt8(value))
    }
}
