import Foundation

func driverReadFailure(reason: String?, code: String) -> DriverFeedFailure {
    switch reason {
    case "canonical-onboarding-required": return .canonicalOnboarding
    case "driver-onboarding-required": return .onboarding
    case "account-restricted": return .restricted
    case "thread-unavailable": return .unavailable
    default:
        switch code {
        case "UNAUTHENTICATED", "PERMISSION_DENIED": return .restricted
        default: return .error
        }
    }
}

func driverPagePayload(_ after: DriverCursor?) -> [String: Any] {
    var fields: [String: Any] = ["limit": driverFeedPageSize]
    if let after {
        fields["cursor"] = ["seconds": after.seconds, "nanoseconds": after.nanoseconds, "id": after.id]
    }
    return fields
}

func driverWireList(_ fields: [String: Any], key: String) throws -> [[String: Any]] {
    guard let values = fields[key] as? [[String: Any]], values.count <= driverFeedPageSize else {
        throw DriverFeedFailure.error
    }
    return values
}

func driverWireCursor(_ fields: [String: Any]) throws -> DriverCursor? {
    guard let raw = fields["nextCursor"] else { throw DriverFeedFailure.error }
    if raw is NSNull { return nil }
    guard let cursor = raw as? [String: Any], let seconds = cursor["seconds"] as? NSNumber,
        let nanos = cursor["nanoseconds"] as? NSNumber, let id = cursor["id"] as? String,
        seconds.doubleValue.isFinite, seconds.doubleValue.rounded() == seconds.doubleValue,
        (-62135596800...253402300799).contains(seconds.int64Value),
        nanos.doubleValue.isFinite, nanos.doubleValue.rounded() == nanos.doubleValue,
        (0...999999999).contains(nanos.int64Value),
        id.range(of: "^[A-Za-z0-9_-]{8,128}$", options: .regularExpression) != nil else {
        throw DriverFeedFailure.error
    }
    return DriverCursor(seconds: seconds.int64Value, nanoseconds: nanos.int32Value, id: id)
}
