import Foundation

/// One route point accepted by the drive recorder. The wire shape mirrors
/// `saved-drives.schema.json#/$defs/routePoint` exactly.
struct RecordedDrivePoint: Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    let timestampMilliseconds: Int64

    init?(fix: LocationFix) {
        let timestampMs = (fix.timestamp.timeIntervalSince1970 * 1_000).rounded()
        guard fix.latitude.isFinite, (-90...90).contains(fix.latitude),
              fix.longitude.isFinite, (-180...180).contains(fix.longitude),
              timestampMs.isFinite, timestampMs > 0, timestampMs < Double(Int64.max)
        else { return nil }
        latitude = fix.latitude
        longitude = fix.longitude
        timestampMilliseconds = Int64(timestampMs)
    }

    init(latitude: Double, longitude: Double, timestampMilliseconds: Int64) {
        self.latitude = latitude
        self.longitude = longitude
        self.timestampMilliseconds = timestampMilliseconds
    }

    var wireValue: [String: Any] {
        [
            "latitude": latitude,
            "longitude": longitude,
            "timestampMs": timestampMilliseconds,
        ]
    }
}

struct DriveRecordingContext: Equatable, Sendable {
    let sourceSessionId: String
    let vehicleId: String?
    let carImagePath: String?
    let convoyMembers: [ConvoyDriveMember]
    let expiresAt: Date?
    /// Local journal metadata only: a convoy-to-solo handoff keeps one saved drive.
    let continuationSessionId: String?

    init(sourceSessionId: String, vehicleId: String?, carImagePath: String?,
         convoyMembers: [ConvoyDriveMember], expiresAt: Date? = nil,
         continuationSessionId: String? = nil) {
        self.sourceSessionId = sourceSessionId
        self.vehicleId = vehicleId
        self.carImagePath = carImagePath
        self.convoyMembers = convoyMembers
        self.expiresAt = expiresAt
        self.continuationSessionId = continuationSessionId
    }

    var liveSessionId: String { continuationSessionId ?? sourceSessionId }

    func continuing(with next: DriveRecordingContext) -> DriveRecordingContext {
        DriveRecordingContext(sourceSessionId: sourceSessionId,
            vehicleId: next.vehicleId ?? vehicleId, carImagePath: next.carImagePath ?? carImagePath,
            convoyMembers: convoyMembers, expiresAt: next.expiresAt,
            continuationSessionId: next.sourceSessionId)
    }
}

/// Bounded, coordinate-free evidence from the recording stream. The typed
/// save request carries only totals; rejected locations and uptime never leave
/// the provider/recorder seam. Kept out of public error-report messages.
struct DriveRecordingDiagnostics: Equatable, Codable, Sendable {
    static let maximumCount = 10_000_000
    static let maximumGapMs = 604_800_000
    var version = 1
    var algorithmVersion = "recording-quality-v1"
    var receivedPoints = 0
    var acceptedPoints = 0
    var rejectedInvalid = 0
    var rejectedAccuracy = 0
    var rejectedStale = 0
    var rejectedTime = 0
    var rejectedSpeed = 0
    var droppedCapacity = 0
    var droppedStationary = 0
    var droppedThrottled = 0
    var maxGapMs = 0
    var gapCount = 0
    var resumedCount = 0

    var isValid: Bool {
        version == 1 && algorithmVersion == "recording-quality-v1" &&
            maxGapMs >= 0 && maxGapMs <= Self.maximumGapMs &&
            [receivedPoints, acceptedPoints, rejectedInvalid, rejectedAccuracy,
             rejectedStale, rejectedTime, rejectedSpeed, droppedCapacity,
             droppedStationary, droppedThrottled, gapCount, resumedCount]
                .allSatisfy { $0 >= 0 && $0 <= Self.maximumCount }
    }

    var wireValue: [String: Any] {
        ["version": version, "algorithmVersion": algorithmVersion,
         "receivedPoints": receivedPoints, "acceptedPoints": acceptedPoints,
         "rejectedInvalid": rejectedInvalid, "rejectedAccuracy": rejectedAccuracy,
         "rejectedStale": rejectedStale, "rejectedTime": rejectedTime,
         "rejectedSpeed": rejectedSpeed, "droppedCapacity": droppedCapacity,
         "droppedStationary": droppedStationary, "droppedThrottled": droppedThrottled,
         "maxGapMs": maxGapMs, "gapCount": gapCount, "resumedCount": resumedCount]
    }

    static func increment(_ count: inout Int) { count = min(maximumCount, count + 1) }

    mutating func reject(_ reason: RecordingLocationRejection) {
        switch reason {
        case .invalid: Self.increment(&rejectedInvalid)
        case .accuracy: Self.increment(&rejectedAccuracy)
        case .stale: Self.increment(&rejectedStale)
        case .time: Self.increment(&rejectedTime)
        case .speed: Self.increment(&rejectedSpeed)
        }
    }

    mutating func reconcile(acceptedCount: Int) {
        acceptedPoints = min(Self.maximumCount, acceptedCount)
        // Route append and summary checkpoint are independently crash-safe. A
        // newer complete route line may survive a kill before its checkpoint.
        let accounted = acceptedPoints + rejectedInvalid + rejectedAccuracy + rejectedStale +
            rejectedTime + rejectedSpeed + droppedCapacity + droppedStationary + droppedThrottled
        receivedPoints = min(Self.maximumCount, max(receivedPoints, accounted))
    }
}

struct DriveSaveRequest: Equatable, Sendable {
    static let maximumTitleLength = 200

    let startedAt: Date
    let endedAt: Date
    let points: [RecordedDrivePoint]
    let context: DriveRecordingContext
    let title: String?
    var recordingDiagnostics: DriveRecordingDiagnostics? = nil

    var payload: [String: Any] {
        var result: [String: Any] = [
            "startedAt": startedAt.formatted(.iso8601),
            "endedAt": endedAt.formatted(.iso8601),
            "sourceSessionId": context.sourceSessionId,
        ]
        if !points.isEmpty { result["routePoints"] = points.map(\.wireValue) }
        if let recordingDiagnostics { result["recordingDiagnostics"] = recordingDiagnostics.wireValue }
        if let title = Self.normalizedTitle(title) { result["title"] = title }
        if let vehicleId = Self.nonBlank(context.vehicleId) { result["vehicleId"] = vehicleId }
        if let carImagePath = Self.nonBlank(context.carImagePath) {
            result["carImagePath"] = carImagePath
        }
        let members = ConvoyDriveMembers.requestList(context.convoyMembers)
        if !members.isEmpty { result["convoyMembers"] = members }
        return result
    }

    static func normalizedTitle(_ value: String?) -> String? {
        guard let value = nonBlank(value) else { return nil }
        var result = ""
        var utf16Count = 0
        for character in value {
            let width = String(character).utf16.count
            guard utf16Count + width <= maximumTitleLength else { break }
            result.append(character)
            utf16Count += width
        }
        return result.isEmpty ? nil : result
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty else { return nil }
        return value
    }
}

struct DriveSaveResult: Equatable, Sendable {
    let rideId: String
    let routePath: String?
    let alreadySaved: Bool
}

struct DriveRecordingSummary: Equatable, Sendable {
    let pointCount: Int
    let durationSeconds: Int
    let distanceMeters: Double?
    let averageSpeedMetersPerSecond: Double?
}

/// A bounded in-memory recorder. Exact coordinates stay private in memory/the
/// protected journal until live-session teardown auto-saves them to owner-only
/// History; a definitive refusal releases them when the user closes the prompt.
struct DriveRecorder: Sendable {
    static let maximumRoutePoints = 20_000
    static let minimumSampleInterval: TimeInterval = 2
    static let maximumPlausibleSpeedMetersPerSecond = 55.6

#if DEBUG
    typealias SegmentEvaluationObserver = @Sendable (
        _ start: RecordedDrivePoint,
        _ end: RecordedDrivePoint
    ) -> Void

    private static let segmentEvaluationObserverLock = NSLock()
    nonisolated(unsafe) private static var segmentEvaluationObserver: SegmentEvaluationObserver?
#endif

    let startedAt: Date
    private(set) var context: DriveRecordingContext
    private(set) var points: [RecordedDrivePoint] = []
    private var acceptedDistanceMeters: Double = 0
    private(set) var recordingDiagnostics = DriveRecordingDiagnostics()
    private var lastReceiptUptime: TimeInterval?

    init(startedAt: Date, context: DriveRecordingContext) {
        self.startedAt = startedAt
        self.context = context
        points.reserveCapacity(1_024)
    }

    init(startedAt: Date, context: DriveRecordingContext, restoring restored: [RecordedDrivePoint],
         diagnostics: DriveRecordingDiagnostics? = nil) {
        self.init(startedAt: startedAt, context: context)
        for point in restored.prefix(Self.maximumRoutePoints) {
            _ = add(point)
        }
        recordingDiagnostics = diagnostics.flatMap { $0.isValid ? $0 : nil }
            ?? DriveRecordingDiagnostics()
        recordingDiagnostics.reconcile(acceptedCount: points.count)
        DriveRecordingDiagnostics.increment(&recordingDiagnostics.resumedCount)
        // A monotonic clock belongs to one process/boot epoch; never restore it.
        lastReceiptUptime = nil
    }

    mutating func enrichContext(_ update: DriveRecordingContext) {
        guard update.sourceSessionId == context.sourceSessionId else { return }
        context = DriveRecordingContext(
            sourceSessionId: context.sourceSessionId,
            vehicleId: update.vehicleId ?? context.vehicleId,
            carImagePath: update.carImagePath ?? context.carImagePath,
            convoyMembers: update.convoyMembers.isEmpty ? context.convoyMembers : update.convoyMembers,
            expiresAt: update.expiresAt ?? context.expiresAt,
            continuationSessionId: update.continuationSessionId ?? context.continuationSessionId
        )
    }

    @discardableResult
    mutating func add(_ fix: LocationFix) -> Bool {
        add(RecordingLocationEvent(decision: .accepted(fix),
            receivedUptime: RecordingLocationEvent.receiptUptime))
    }

    @discardableResult
    mutating func add(_ event: RecordingLocationEvent) -> Bool {
        DriveRecordingDiagnostics.increment(&recordingDiagnostics.receivedPoints)
        if event.receivedUptime.isFinite, event.receivedUptime >= 0 {
            if let previous = lastReceiptUptime, event.receivedUptime > previous {
                let gap = min(Double(DriveRecordingDiagnostics.maximumGapMs),
                    (event.receivedUptime - previous) * 1_000)
                let gapMs = Int(gap.rounded(.down))
                recordingDiagnostics.maxGapMs = max(recordingDiagnostics.maxGapMs, gapMs)
                if gapMs > 15_000 { DriveRecordingDiagnostics.increment(&recordingDiagnostics.gapCount) }
            }
            lastReceiptUptime = event.receivedUptime
        }
        guard case .accepted(let fix) = event.decision else {
            if case .rejected(let reason) = event.decision { recordingDiagnostics.reject(reason) }
            return false
        }
        guard let point = RecordedDrivePoint(fix: fix) else {
            recordingDiagnostics.reject(.invalid)
            return false
        }
        guard points.count < Self.maximumRoutePoints else {
            DriveRecordingDiagnostics.increment(&recordingDiagnostics.droppedCapacity)
            return false
        }
        // Held parked fixes remain useful to live sharing but never advance
        // the route timestamp or create fictitious driving distance.
        if let last = points.last, last.latitude == point.latitude, last.longitude == point.longitude {
            DriveRecordingDiagnostics.increment(&recordingDiagnostics.droppedStationary)
            return false
        }
        if let last = points.last {
            let delta = Double(point.timestampMilliseconds - last.timestampMilliseconds) / 1_000
            if delta <= 0 {
                recordingDiagnostics.reject(.time)
                return false
            }
            if delta < Self.minimumSampleInterval {
                DriveRecordingDiagnostics.increment(&recordingDiagnostics.droppedThrottled)
                return false
            }
        }
        guard add(point) else { return false }
        DriveRecordingDiagnostics.increment(&recordingDiagnostics.acceptedPoints)
        return true
    }

    @discardableResult
    private mutating func add(_ point: RecordedDrivePoint) -> Bool {
        guard points.count < Self.maximumRoutePoints else { return false }
        if let previous = points.last {
            let delta = Double(point.timestampMilliseconds - previous.timestampMilliseconds) / 1_000
            guard delta >= Self.minimumSampleInterval else { return false }
            acceptedDistanceMeters += Self.segmentDistance(from: previous, to: point)
        }
        points.append(point)
        return true
    }

    func summary(endedAt: Date) -> DriveRecordingSummary {
        let duration = max(0, Int(endedAt.timeIntervalSince(startedAt).rounded()))
        return DriveRecordingSummary(
            pointCount: points.count,
            durationSeconds: duration,
            distanceMeters: points.count >= 2 ? acceptedDistanceMeters : nil,
            averageSpeedMetersPerSecond: duration > 0 && points.count >= 2
                ? acceptedDistanceMeters / Double(duration)
                : nil
        )
    }

    func request(endedAt: Date, title: String?) -> DriveSaveRequest {
        let lastFix = points.last.map {
            Date(timeIntervalSince1970: Double($0.timestampMilliseconds) / 1_000)
        }
        let minimumEnd = startedAt.addingTimeInterval(0.001)
        let effectiveEnd = max(endedAt, lastFix ?? endedAt, minimumEnd)
        return DriveSaveRequest(
            startedAt: startedAt,
            endedAt: effectiveEnd,
            points: points,
            context: context,
            title: title,
            recordingDiagnostics: recordingDiagnostics
        )
    }

    static func totalDistance(_ points: [RecordedDrivePoint]) -> Double {
        guard points.count >= 2 else { return 0 }
        return zip(points, points.dropFirst()).reduce(0) { total, pair in
            total + segmentDistance(from: pair.0, to: pair.1)
        }
    }

    static func segmentDistance(from start: RecordedDrivePoint, to end: RecordedDrivePoint) -> Double {
#if DEBUG
        segmentEvaluationObserverLock.lock()
        let observer = segmentEvaluationObserver
        segmentEvaluationObserverLock.unlock()
        observer?(start, end)
#endif
        let delta = Double(end.timestampMilliseconds - start.timestampMilliseconds) / 1_000
        guard delta > 0 else { return 0 }
        let distance = LiveShareCadence.distanceMeters(
            lat1: start.latitude,
            lon1: start.longitude,
            lat2: end.latitude,
            lon2: end.longitude
        )
        return distance / delta <= maximumPlausibleSpeedMetersPerSecond ? distance : 0
    }

#if DEBUG
    static func withSegmentEvaluationObserver<Result>(
        _ observer: @escaping SegmentEvaluationObserver,
        operation: () throws -> Result
    ) rethrows -> Result {
        segmentEvaluationObserverLock.lock()
        precondition(segmentEvaluationObserver == nil, "Only one segment evaluation observer is supported")
        segmentEvaluationObserver = observer
        segmentEvaluationObserverLock.unlock()
        defer {
            segmentEvaluationObserverLock.lock()
            segmentEvaluationObserver = nil
            segmentEvaluationObserverLock.unlock()
        }
        return try operation()
    }
#endif
}

extension ConvoyDriveMembers {
    static func requestList(_ members: [ConvoyDriveMember]) -> [[String: String]] {
        var seen = Set<String>()
        return members.compactMap { member in
            let uid = member.uid.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !uid.isEmpty, seen.insert(uid).inserted else { return nil }
            var result = ["uid": uid]
            if let name = member.displayName?.trimmingCharacters(in: .whitespacesAndNewlines),
               !name.isEmpty { result["displayName"] = name }
            if let path = member.avatarPath?.trimmingCharacters(in: .whitespacesAndNewlines),
               !path.isEmpty { result["avatarPath"] = path }
            return result
        }
        .prefix(maxMembers)
        .map { $0 }
    }
}
