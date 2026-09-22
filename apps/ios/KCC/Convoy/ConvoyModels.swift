import Foundation
import SwiftUI

enum ConvoyStatus: String, Equatable, Sendable {
    case forming
    case active
    case ended
}

enum ConvoyInviteStatus: String, Equatable, Sendable {
    case invited
    case accepted
    case declined
}

enum ConvoyRole: String, Equatable, Sendable {
    case owner
    case member
}

struct ConvoyMember: Equatable, Sendable, Identifiable {
    let uid: String
    let displayName: String?
    let role: ConvoyRole
    let inviteStatus: ConvoyInviteStatus

    var id: String { uid }
}

struct ConvoyViewer: Equatable, Sendable {
    let role: ConvoyRole?
    let inviteStatus: ConvoyInviteStatus

    init(inviteStatus: ConvoyInviteStatus, role: ConvoyRole? = nil) {
        self.role = role
        self.inviteStatus = inviteStatus
    }
}

struct ConvoySummaryStats: Equatable, Sendable {
    let durationSeconds: Int
    let participantUids: [String]
    let participantCount: Int
    let distanceMeters: Double?
}

struct ConvoyRecapState: Equatable, Sendable {
    let durationSeconds: Int
    let participants: [ConvoyMember]
    let participantCount: Int
    let distanceMeters: Double?
}

struct ConvoyItem: Equatable, Sendable, Identifiable {
    let convoyId: String
    let title: String?
    let status: ConvoyStatus
    let members: [ConvoyMember]
    let viewer: ConvoyViewer?
    let createdAt: Date?
    let livePositionUids: [String]
    let summary: ConvoySummaryStats?

    init(
        convoyId: String,
        title: String?,
        status: ConvoyStatus,
        members: [ConvoyMember],
        viewer: ConvoyViewer?,
        createdAt: Date?,
        livePositionUids: [String] = [],
        summary: ConvoySummaryStats? = nil
    ) {
        self.convoyId = convoyId
        self.title = title
        self.status = status
        self.members = members
        self.viewer = viewer
        self.createdAt = createdAt
        self.livePositionUids = livePositionUids
        self.summary = summary
    }

    var id: String { convoyId }

    var ownerName: String? {
        members.first(where: { $0.role == .owner })?.displayName
    }

    var acceptedMemberCount: Int {
        members.filter { $0.inviteStatus == .accepted }.count
    }

    var viewerIsOwner: Bool { viewer?.role == .owner }

    var acceptedMembers: [ConvoyMember] {
        members.filter { $0.inviteStatus == .accepted }
    }

    var pendingMembers: [ConvoyMember] {
        members.filter { $0.inviteStatus == .invited }
    }

    var recap: ConvoyRecapState? {
        guard status == .ended, let summary else { return nil }
        let membersByUid = members.reduce(into: [String: ConvoyMember]()) { result, member in
            result[member.uid] = member
        }
        let participants = summary.participantUids.map { uid in
            membersByUid[uid] ?? ConvoyMember(
                uid: uid, displayName: nil, role: .member, inviteStatus: .accepted
            )
        }
        return ConvoyRecapState(
            durationSeconds: summary.durationSeconds,
            participants: participants,
            participantCount: max(summary.participantCount, summary.participantUids.count),
            distanceMeters: summary.distanceMeters
        )
    }
}

enum ConvoyProfileHydration {
    static func apply(_ convoy: ConvoyItem, names: [String: String]) -> ConvoyItem {
        guard !names.isEmpty else { return convoy }
        return ConvoyItem(
            convoyId: convoy.convoyId,
            title: convoy.title,
            status: convoy.status,
            members: convoy.members.map { member in
                ConvoyMember(
                    uid: member.uid,
                    displayName: names[member.uid] ?? member.displayName,
                    role: member.role,
                    inviteStatus: member.inviteStatus
                )
            },
            viewer: convoy.viewer,
            createdAt: convoy.createdAt,
            livePositionUids: convoy.livePositionUids,
            summary: convoy.summary
        )
    }
}

struct ConvoyManagementSnapshot: Equatable, Sendable {
    let convoys: [ConvoyItem]
    let pendingInvites: [ConvoyItem]
    let isExhaustive: Bool

    var myConvoys: [ConvoyItem] {
        let pendingIds = Set(pendingInvites.map(\.convoyId))
        return convoys.filter { !pendingIds.contains($0.convoyId) }
    }

    var hasActiveConvoy: Bool {
        convoys.contains {
            $0.status != .ended && $0.viewer?.inviteStatus == .accepted
        }
    }

    /// False means the bounded live-membership scan could not rule out an older
    /// active membership, so joining must stay blocked. Ended history may still
    /// be capped when this is true.
    var canJoinAnotherConvoy: Bool { !hasActiveConvoy && isExhaustive }

    func preservingKnownActive(from previous: ConvoyManagementSnapshot?) -> Self {
        guard !isExhaustive, let previous,
              let active = ConvoyBarLogic.activeConvoy(in: previous),
              !convoys.contains(where: { $0.convoyId == active.convoyId })
        else { return self }
        return Self(
            convoys: [active] + convoys,
            pendingInvites: pendingInvites,
            isExhaustive: false
        )
    }
}

enum ConvoyAction: String, Equatable, Sendable {
    case accept
    case decline
}

enum ConvoyActionError: Equatable, Sendable {
    case signedOut
    case notMember
    case invalid
    case notFound
    case inviteGone
    case alreadyInConvoy
    case membershipUncertain
    case unresolvedPrecondition
    case notLeader
    case cannotStart
    case alreadyEnded
    case leaveFailed
    case noInvitees
    case generic
}

enum ConvoyLifecycleAction: String, Equatable, Sendable {
    case start
    case end
    case leave
}

enum ConvoyLeaveOutcome: String, Equatable, Sendable {
    case left
    case leftAndEnded = "left_and_ended"
}

struct ConvoyLeaveResult: Equatable, Sendable {
    let outcome: ConvoyLeaveOutcome
    let newLeaderUid: String?
}

struct ConvoyInviteResult: Equatable, Sendable {
    let convoy: ConvoyItem
    let invitedCount: Int
    let skippedCount: Int
}

enum ConvoyLifecycleResult: Equatable, Sendable {
    case updated(ConvoyItem)
    case left(ConvoyLeaveResult)
    case failed(ConvoyActionError)
}

enum ConvoyInviteMutationResult: Equatable, Sendable {
    case completed(ConvoyInviteResult)
    case failed(ConvoyActionError)
}

enum ConvoyExitChoice: Equatable, Sendable {
    case leaveOrEnd
    case endOnly
    case leaveOnly
    case leaveEndsConvoy
}

enum ConvoyBarLogic {
    static let minimumRemainingMembers = 2
    static let maximumConvoySize = 25
    static let maximumInviteBatchSize = 25

    static func maximumInviteSelection(for convoy: ConvoyItem) -> Int {
        min(maximumInviteBatchSize, max(maximumConvoySize - convoy.members.count, 0))
    }

    static func activeConvoy(in snapshot: ConvoyManagementSnapshot) -> ConvoyItem? {
        let joined = snapshot.convoys.filter {
            $0.status != .ended && $0.viewer?.inviteStatus == .accepted
        }
        return joined.first(where: { $0.status == .active }) ?? joined.first
    }

    static func exitChoice(viewerIsOwner: Bool, acceptedMemberCount: Int) -> ConvoyExitChoice {
        let remaining = max(acceptedMemberCount - 1, 0)
        let survives = remaining >= minimumRemainingMembers
        switch (viewerIsOwner, survives) {
        case (true, true): return .leaveOrEnd
        case (true, false): return .endOnly
        case (false, true): return .leaveOnly
        case (false, false): return .leaveEndsConvoy
        }
    }
}

// MARK: - Live map awareness

struct ConvoyMemberPosition: Equatable, Sendable, Identifiable {
    let uid: String
    let latitude: Double
    let longitude: Double
    let displayName: String?
    let imagePath: String?
    let updatedAt: Date?
    let accuracyMeters: Double?

    var id: String { uid }

    func isFresh(at now: Date = Date()) -> Bool {
        updatedAt.map { now.timeIntervalSince($0) <= ConvoyArrowPlanner.staleAfter } ?? true
    }

    init(
        uid: String,
        latitude: Double,
        longitude: Double,
        displayName: String? = nil,
        imagePath: String? = nil,
        updatedAt: Date? = nil,
        accuracyMeters: Double? = nil
    ) {
        self.uid = uid
        self.latitude = latitude
        self.longitude = longitude
        self.displayName = displayName
        self.imagePath = imagePath
        self.updatedAt = updatedAt
        self.accuracyMeters = accuracyMeters
    }

    init(marker: LiveMarker) {
        uid = marker.uid
        latitude = marker.latitude
        longitude = marker.longitude
        displayName = marker.displayName
        imagePath = marker.imagePath
        updatedAt = marker.recordedAt
        accuracyMeters = marker.accuracyMeters
    }
}

enum ConvoyFocusMode: Equatable, Sendable {
    case me
    case convoy
}

enum ConvoyImageLookupPolicy {
    static let retryAfter: TimeInterval = 5 * 60

    static func shouldAttempt(lastAttempt: Date?, now: Date = Date()) -> Bool {
        guard let lastAttempt else { return true }
        return now.timeIntervalSince(lastAttempt) >= retryAfter
    }
}

enum ConvoyPositionQuality {
    static let trustedAccuracyMeters = 50.0
    static let maximumUsableAccuracyMeters = 200.0
    static let corroborationTriggerMeters = 500.0
    static let corroborationRadiusMeters = 250.0
    static let maximumPlausibleSpeedMetersPerSecond = 55.6

    enum Verdict: Equatable {
        case accept
        case hold
        case reject
    }

    static func judge(
        _ candidate: ConvoyMemberPosition,
        previous: ConvoyMemberPosition?,
        pending: ConvoyMemberPosition?
    ) -> Verdict {
        guard candidate.latitude.isFinite, candidate.longitude.isFinite,
              abs(candidate.latitude) <= 90, abs(candidate.longitude) <= 180 else {
            return .reject
        }
        let accuracy = candidate.accuracyMeters.flatMap {
            $0.isFinite && $0 >= 0 ? $0 : nil
        }
        if let accuracy, accuracy > maximumUsableAccuracyMeters { return .reject }
        guard let previous else { return .accept }

        let interval = candidate.updatedAt.flatMap { candidateDate in
            previous.updatedAt.map { candidateDate.timeIntervalSince($0) }
        }
        if let interval, interval <= 0 { return .reject }
        let distance = LiveShareCadence.distanceMeters(
            lat1: previous.latitude, lon1: previous.longitude,
            lat2: candidate.latitude, lon2: candidate.longitude
        )
        guard distance.isFinite else { return .reject }
        if let interval, distance / interval > maximumPlausibleSpeedMetersPerSecond {
            return .reject
        }
        if distance <= corroborationTriggerMeters
            || (accuracy.map { $0 <= trustedAccuracyMeters } ?? false) {
            return .accept
        }
        if let pending {
            let corroborationDistance = LiveShareCadence.distanceMeters(
                lat1: pending.latitude, lon1: pending.longitude,
                lat2: candidate.latitude, lon2: candidate.longitude
            )
            if corroborationDistance.isFinite,
               corroborationDistance <= corroborationRadiusMeters {
                return .accept
            }
        }
        return .hold
    }
}

struct ConvoyOnScreenPlacement: Equatable, Sendable, Identifiable {
    let member: ConvoyMemberPosition
    let point: MapScreenPoint
    var id: String { member.uid }
}

struct ConvoyOffScreenPlacement: Equatable, Sendable, Identifiable {
    let member: ConvoyMemberPosition
    let point: MapScreenPoint
    let angleDegrees: Double
    let distanceMeters: Double
    let extraCount: Int
    var id: String { member.uid }
}

struct ConvoyPlacements: Equatable, Sendable {
    let onScreen: [ConvoyOnScreenPlacement]
    let offScreen: [ConvoyOffScreenPlacement]
}

enum ConvoyArrowPlanner {
    static let staleAfter: TimeInterval = 4 * 60
    static let maximumArrows = 4
    static let sectorDegrees = 30.0
    static let minimumArrowDistanceMeters = 5.0

    static func plan(
        members: [ConvoyMemberPosition],
        camera: MapCameraSnapshot,
        viewportWidth: Double,
        viewportHeight: Double,
        edgeInset: Double,
        viewportMargin: Double = 24,
        now: Date = Date(),
        project: (ConvoyMemberPosition) -> MapScreenPoint?
    ) -> ConvoyPlacements {
        guard viewportWidth > 0, viewportHeight > 0 else {
            return ConvoyPlacements(onScreen: [], offScreen: [])
        }
        var onScreen: [ConvoyOnScreenPlacement] = []
        var candidates: [(ConvoyMemberPosition, Double, Double)] = []
        for member in members where !isStale(member, now: now) {
            let distance = LiveShareCadence.distanceMeters(
                lat1: camera.latitude, lon1: camera.longitude,
                lat2: member.latitude, lon2: member.longitude
            )
            let angle = normalized(
                initialBearing(
                    fromLatitude: camera.latitude, fromLongitude: camera.longitude,
                    toLatitude: member.latitude, toLongitude: member.longitude
                ) - camera.bearing
            )
            if let point = project(member), point.trustworthy,
               point.x >= viewportMargin, point.y >= viewportMargin,
               point.x <= viewportWidth - viewportMargin,
               point.y <= viewportHeight - viewportMargin {
                onScreen.append(ConvoyOnScreenPlacement(member: member, point: point))
            } else if distance >= minimumArrowDistanceMeters {
                candidates.append((member, angle, distance))
            }
        }

        let sectors = Dictionary(grouping: candidates) { candidate in
            Int(floor((candidate.1 + sectorDegrees / 2) / sectorDegrees)) % Int(360 / sectorDegrees)
        }
        var merged = sectors.values.compactMap { group -> (ConvoyMemberPosition, Double, Double, Int)? in
            guard let nearest = group.min(by: {
                $0.2 == $1.2 ? $0.0.uid < $1.0.uid : $0.2 < $1.2
            }) else { return nil }
            return (nearest.0, nearest.1, nearest.2, group.count - 1)
        }.sorted {
            $0.2 == $1.2 ? $0.0.uid < $1.0.uid : $0.2 < $1.2
        }
        if merged.count > maximumArrows {
            let dropped = merged.dropFirst(maximumArrows).reduce(0) { $0 + $1.3 + 1 }
            merged = Array(merged.prefix(maximumArrows))
            if dropped > 0, !merged.isEmpty { merged[0].3 += dropped }
        }
        let offScreen = merged.map { member, angle, distance, extra in
            ConvoyOffScreenPlacement(
                member: member,
                point: edgePoint(
                    angleDegrees: angle, width: viewportWidth,
                    height: viewportHeight, inset: edgeInset
                ),
                angleDegrees: angle,
                distanceMeters: distance,
                extraCount: extra
            )
        }
        return ConvoyPlacements(onScreen: onScreen, offScreen: offScreen)
    }

    static func edgePoint(angleDegrees: Double, width: Double, height: Double, inset: Double) -> MapScreenPoint {
        let centerX = width / 2, centerY = height / 2
        let halfWidth = centerX - inset, halfHeight = centerY - inset
        guard halfWidth > 0, halfHeight > 0 else { return MapScreenPoint(x: centerX, y: centerY) }
        let radians = normalized(angleDegrees) * .pi / 180
        let dx = sin(radians), dy = -cos(radians)
        let vertical = abs(dx) < 0.000_001 ? Double.greatestFiniteMagnitude : halfWidth / abs(dx)
        let horizontal = abs(dy) < 0.000_001 ? Double.greatestFiniteMagnitude : halfHeight / abs(dy)
        let scale = min(vertical, horizontal)
        return MapScreenPoint(x: centerX + dx * scale, y: centerY + dy * scale)
    }

    static func initialBearing(
        fromLatitude: Double, fromLongitude: Double,
        toLatitude: Double, toLongitude: Double
    ) -> Double {
        let fromLat = fromLatitude * .pi / 180
        let toLat = toLatitude * .pi / 180
        let delta = (toLongitude - fromLongitude) * .pi / 180
        return normalized(atan2(
            sin(delta) * cos(toLat),
            cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(delta)
        ) * 180 / .pi)
    }

    private static func isStale(_ member: ConvoyMemberPosition, now: Date) -> Bool {
        member.updatedAt.map { now.timeIntervalSince($0) > staleAfter } ?? false
    }

    private static func normalized(_ degrees: Double) -> Double {
        let result = degrees.truncatingRemainder(dividingBy: 360)
        return result < 0 ? result + 360 : result
    }
}

@MainActor
@Observable
final class ConvoyAwarenessCoordinator {
    private(set) var positions: [String: ConvoyMemberPosition] = [:]
    private(set) var imageURLs: [String: URL] = [:]
    var focusMode: ConvoyFocusMode = .me

    @ObservationIgnored private var subscriptionKey = ""
    @ObservationIgnored private var activeConvoyId: String?
    @ObservationIgnored private var ownUid: String?
    @ObservationIgnored private var imageLookupAttempts: [String: Date] = [:]
    @ObservationIgnored private var imageTasks: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var pendingPositions: [String: ConvoyMemberPosition] = [:]
    @ObservationIgnored nonisolated(unsafe) private var tasks: [Task<Void, Never>] = []

    func sync(convoy: ConvoyItem?, repository: LiveLocationRepository?, currentUid: String?) {
        let convoyId = convoy?.convoyId
        if convoyId != activeConvoyId {
            focusMode = .me
            activeConvoyId = convoyId
        }
        let uids = convoy?.livePositionUids.filter { !$0.isEmpty } ?? []
        let key = "\(convoyId ?? "")|\(currentUid ?? "")|\(uids.sorted().joined(separator: ","))"
        guard key != subscriptionKey else { return }
        cancelSubscriptions()
        subscriptionKey = key
        ownUid = currentUid
        positions = [:]
        pendingPositions = [:]
        imageURLs = [:]
        imageLookupAttempts = [:]
        guard let repository, convoy != nil else { return }
        for uid in Set(uids) {
            tasks.append(Task { [weak self, repository] in
                for await marker in repository.latestUpdates(uid: uid) {
                    guard !Task.isCancelled, let self else { return }
                    if let marker {
                        let position = ConvoyMemberPosition(marker: marker)
                        switch ConvoyPositionQuality.judge(
                            position,
                            previous: self.positions[uid],
                            pending: self.pendingPositions[uid]
                        ) {
                        case .accept:
                            self.positions[uid] = position
                            self.pendingPositions.removeValue(forKey: uid)
                        case .hold:
                            self.pendingPositions[uid] = position
                            continue
                        case .reject:
                            self.pendingPositions.removeValue(forKey: uid)
                            continue
                        }
                        if let path = marker.imagePath, self.imageURLs[path] == nil,
                           ConvoyImageLookupPolicy.shouldAttempt(
                               lastAttempt: self.imageLookupAttempts[path]
                           ) {
                            self.resolveImageURL(
                                path: path,
                                repository: repository,
                                subscriptionKey: key
                            )
                        }
                    } else {
                        self.positions.removeValue(forKey: uid)
                        self.pendingPositions.removeValue(forKey: uid)
                    }
                }
            })
        }
    }

    var visibleMembers: [ConvoyMemberPosition] {
        positions.values.filter { $0.uid != ownUid }
    }

    func fitPoints(now: Date = Date()) -> [MapPoint]? {
        guard focusMode == .convoy else { return nil }
        let fresh = positions.values.filter { $0.isFresh(at: now) }
        guard fresh.contains(where: { $0.uid != ownUid }), fresh.count >= 2 else { return nil }
        return fresh.map { MapPoint(longitude: $0.longitude, latitude: $0.latitude) }
    }

    func ownPoint(now: Date = Date()) -> MapPoint? {
        guard let ownUid, let position = positions[ownUid], position.isFresh(at: now) else {
            return nil
        }
        return MapPoint(longitude: position.longitude, latitude: position.latitude)
    }

    func setPositionsForTest(
        _ positions: [String: ConvoyMemberPosition],
        ownUid: String?
    ) {
        self.positions = positions
        self.ownUid = ownUid
        pendingPositions = [:]
    }

    private func resolveImageURL(
        path: String,
        repository: LiveLocationRepository,
        subscriptionKey expectedKey: String
    ) {
        // Record and detach before awaiting Storage so a slow image lookup never
        // blocks the latest-position stream for this member.
        imageLookupAttempts[path] = Date()
        imageTasks[path] = Task { [weak self, repository] in
            let url = await repository.imageDownloadURL(for: path)
            guard let self else { return }
            defer { self.imageTasks.removeValue(forKey: path) }
            guard !Task.isCancelled, self.subscriptionKey == expectedKey, let url else { return }
            self.imageURLs[path] = url
        }
    }

    func cancelSubscriptions() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        imageTasks.values.forEach { $0.cancel() }
        imageTasks.removeAll()
    }

    deinit {
        tasks.forEach { $0.cancel() }
        imageTasks.values.forEach { $0.cancel() }
    }
}

enum ConvoyManagementListResult: Equatable, Sendable {
    case loaded(ConvoyManagementSnapshot)
    case failed(ConvoyActionError)
}

enum ConvoyRespondResult: Equatable, Sendable {
    case updated(ConvoyItem)
    case failed(ConvoyActionError)
}

enum ConvoyManagementParser {
    static let listLimit = 200

    static func parseList(_ data: [String: Any]?) -> ConvoyManagementSnapshot {
        let rawConvoys = data?["convoys"] as? [Any]
        let rawPending = data?["pendingInvites"] as? [Any]
        let convoys = parseItems(data?["convoys"])
        let pendingInvites = parseItems(data?["pendingInvites"])
            .filter { $0.status != .ended && $0.viewer?.inviteStatus == .invited }
        let validPayload = rawConvoys != nil && rawPending != nil
            && convoys.count == (rawConvoys?.count ?? -1)
            && pendingInvites.count == (rawPending?.count ?? -1)
        return ConvoyManagementSnapshot(
            convoys: convoys,
            pendingInvites: pendingInvites,
            isExhaustive: validPayload &&
                ((data?["isExhaustive"] as? Bool) ?? ((rawConvoys?.count ?? listLimit) < listLimit))
        )
    }

    static func parseRespond(_ data: [String: Any]?) -> ConvoyRespondResult {
        guard let convoy = parseItem(data?["convoy"]) else { return .failed(.generic) }
        return .updated(convoy)
    }

    static func parseLifecycle(
        _ data: [String: Any]?,
        action: ConvoyLifecycleAction
    ) -> ConvoyLifecycleResult {
        if action == .leave {
            guard let outcomeRaw = data?["outcome"] as? String,
                  let outcome = ConvoyLeaveOutcome(rawValue: outcomeRaw)
            else { return .failed(.generic) }
            return .left(
                ConvoyLeaveResult(
                    outcome: outcome,
                    newLeaderUid: clean(data?["newLeaderUid"] as? String)
                )
            )
        }
        guard let convoy = parseItem(data?["convoy"]) else { return .failed(.generic) }
        return .updated(convoy)
    }

    static func parseInvite(_ data: [String: Any]?) -> ConvoyInviteMutationResult {
        guard let convoy = parseItem(data?["convoy"]),
              let invited = data?["invited"] as? [Any],
              let skipped = data?["skipped"] as? [Any]
        else { return .failed(.generic) }
        return .completed(
            ConvoyInviteResult(
                convoy: convoy,
                invitedCount: invited.count,
                skippedCount: skipped.count
            )
        )
    }

    private static func parseItems(_ raw: Any?) -> [ConvoyItem] {
        (raw as? [Any] ?? []).compactMap(parseItem)
    }

    static func parseItem(_ raw: Any?) -> ConvoyItem? {
        guard let data = raw as? [String: Any],
              let convoyId = clean(data["convoyId"] as? String),
              let statusRaw = data["status"] as? String,
              let status = ConvoyStatus(rawValue: statusRaw)
        else { return nil }

        let members = (data["members"] as? [Any] ?? []).compactMap { raw -> ConvoyMember? in
            guard let member = raw as? [String: Any],
                  let uid = clean(member["uid"] as? String),
                  let roleRaw = member["role"] as? String,
                  let role = ConvoyRole(rawValue: roleRaw),
                  let inviteRaw = member["inviteStatus"] as? String,
                  let inviteStatus = ConvoyInviteStatus(rawValue: inviteRaw)
            else { return nil }
            return ConvoyMember(
                uid: uid,
                displayName: clean(member["displayName"] as? String),
                role: role,
                inviteStatus: inviteStatus
            )
        }
        let viewer: ConvoyViewer? = {
            guard let raw = data["viewer"] as? [String: Any],
                  let value = raw["inviteStatus"] as? String,
                  let status = ConvoyInviteStatus(rawValue: value)
            else { return nil }
            let role = (raw["role"] as? String).flatMap(ConvoyRole.init(rawValue:))
            return ConvoyViewer(inviteStatus: status, role: role)
        }()
        let livePositionUids: [String]
        if let explicit = data["livePositionUids"] as? [Any] {
            livePositionUids = explicit.compactMap { clean($0 as? String) }
        } else {
            // Firestore snapshots store the member map but not the callable's
            // derived livePositionUids field. Preserve listeners across that
            // snapshot by deriving the accepted roster locally.
            livePositionUids = members
                .filter { $0.inviteStatus == .accepted }
                .map(\.uid)
        }
        return ConvoyItem(
            convoyId: convoyId,
            title: clean(data["title"] as? String),
            status: status,
            members: members,
            viewer: viewer,
            createdAt: ChannelTime.parseIso(data["createdAt"] as? String),
            livePositionUids: livePositionUids,
            summary: parseSummary(data["summary"])
        )
    }

    private static func parseSummary(_ raw: Any?) -> ConvoySummaryStats? {
        guard let data = raw as? [String: Any] else { return nil }
        let participantUids = (data["participantUids"] as? [Any] ?? [])
            .compactMap { clean($0 as? String) }
        let durationSeconds = max((data["durationSeconds"] as? NSNumber)?.intValue ?? 0, 0)
        let participantCount = max(
            (data["participantCount"] as? NSNumber)?.intValue ?? participantUids.count,
            participantUids.count
        )
        let distanceMeters = (data["distanceMeters"] as? NSNumber).map { max($0.doubleValue, 0) }
        return ConvoySummaryStats(
            durationSeconds: durationSeconds,
            participantUids: participantUids,
            participantCount: participantCount,
            distanceMeters: distanceMeters
        )
    }

    private static func clean(_ value: String?) -> String? {
        guard let value else { return nil }
        let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty ? nil : clean
    }
}

enum ConvoyManagementErrorMapper {
    static func mapList(_ code: KccFunctionsErrorCode) -> ConvoyActionError {
        switch code {
        case .unauthenticated: .signedOut
        case .permissionDenied: .notMember
        case .invalidArgument: .invalid
        default: .generic
        }
    }

    static func mapRespond(_ code: KccFunctionsErrorCode) -> ConvoyActionError {
        switch code {
        case .unauthenticated: .signedOut
        case .permissionDenied: .notMember
        case .invalidArgument: .invalid
        case .notFound: .notFound
        case .failedPrecondition: .unresolvedPrecondition
        default: .generic
        }
    }
    static func mapLifecycle(
        _ code: KccFunctionsErrorCode,
        action: ConvoyLifecycleAction
    ) -> ConvoyActionError {
        switch code {
        case .unauthenticated: .signedOut
        case .permissionDenied: action == .end ? .notLeader : .notMember
        case .invalidArgument: .invalid
        case .notFound: .notFound
        case .failedPrecondition:
            switch action {
            case .start: .cannotStart
            case .end: .alreadyEnded
            case .leave: .leaveFailed
            }
        default: .generic
        }
    }

    static func mapInvite(_ error: KccFunctionsError) -> ConvoyActionError {
        switch error.code {
        case .unauthenticated: .signedOut
        case .permissionDenied: .notMember
        case .invalidArgument: .invalid
        case .notFound: .notFound
        case .failedPrecondition:
            error.reason == .noValidConvoyInvitees ? .noInvitees : .unresolvedPrecondition
        default: .generic
        }
    }
}

enum ConvoyManagementStrings {
    static func errorKey(_ error: ConvoyActionError) -> LocalizedStringKey {
        switch error {
        case .signedOut: "convoy.errorSignedOut"
        case .notMember: "convoy.errorNotMember"
        case .invalid: "convoy.errorInvalid"
        case .notFound: "convoy.errorNotFound"
        case .inviteGone: "convoy.errorInviteGone"
        case .alreadyInConvoy: "convoy.errorAlreadyInConvoy"
        case .membershipUncertain: "convoy.membershipUncertainHint"
        case .unresolvedPrecondition, .generic: "convoy.errorGeneric"
        case .notLeader: "convoy.errorNotLeader"
        case .cannotStart: "convoy.errorCannotStart"
        case .alreadyEnded: "convoy.errorAlreadyEnded"
        case .leaveFailed: "convoy.errorLeaveFailed"
        case .noInvitees: "convoy.errorNoInvitees"
        }
    }
}
