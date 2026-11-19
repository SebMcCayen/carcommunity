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
    static let unreadDisplayMax = 9

    static func unreadBadgeLabel(_ count: Int) -> String? {
        guard count > 0 else { return nil }
        return count > unreadDisplayMax ? "\(unreadDisplayMax)+" : String(count)
    }

    static func chatAccessibilityLabel(_ count: Int) -> String {
        guard count > 0 else { return String(localized: "convoy.barChat") }
        let key = count > unreadDisplayMax ? "convoy.barChatUnreadMany" : "convoy.barChatUnread"
        return String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), min(count, unreadDisplayMax))
    }

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

    static func canOpenMemberProfile(
        memberUid: String,
        viewerUid: String?,
        waiting: Bool
    ) -> Bool {
        guard !waiting, let viewerUid, !viewerUid.isEmpty else { return false }
        return memberUid != viewerUid
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
    let headingDegrees: Double?
    let speedMetersPerSecond: Double?

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
        accuracyMeters: Double? = nil,
        headingDegrees: Double? = nil,
        speedMetersPerSecond: Double? = nil
    ) {
        self.uid = uid
        self.latitude = latitude
        self.longitude = longitude
        self.displayName = displayName
        self.imagePath = imagePath
        self.updatedAt = updatedAt
        self.accuracyMeters = accuracyMeters
        self.headingDegrees = headingDegrees
        self.speedMetersPerSecond = speedMetersPerSecond
    }

    init(marker: LiveMarker) {
        uid = marker.uid
        latitude = marker.latitude
        longitude = marker.longitude
        displayName = marker.displayName
        imagePath = marker.imagePath
        updatedAt = marker.recordedAt
        accuracyMeters = marker.accuracyMeters
        headingDegrees = marker.headingDegrees
        speedMetersPerSecond = marker.speedMetersPerSecond
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
    private static let subscriptionRetryDelay: Duration = .seconds(1)

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
                await self?.observeLatestUpdates(
                    uid: uid,
                    repository: repository,
                    subscriptionKey: key
                )
            })
        }
    }

    var visibleMembers: [ConvoyMemberPosition] {
        positions.values.filter { $0.uid != ownUid }
    }

    func profileTarget(uid: String, at now: Date = Date()) -> ConvoyMemberPosition? {
        visibleMembers.first { $0.uid == uid && $0.isFresh(at: now) }
    }

    func fitPoints(now: Date = Date()) -> [MapPoint]? {
        guard focusMode == .convoy else { return nil }
        let fresh = positions.values.filter { $0.isFresh(at: now) }
        guard fresh.contains(where: { $0.uid != ownUid }), fresh.count >= 2 else { return nil }
        return fresh.map {
            MapPoint(longitude: $0.longitude, latitude: $0.latitude, fitIdentity: $0.uid)
        }
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

    private func observeLatestUpdates(
        uid: String,
        repository: LiveLocationRepository,
        subscriptionKey expectedKey: String
    ) async {
        while !Task.isCancelled, subscriptionKey == expectedKey {
            var shouldRetry = false
            for await event in repository.latestUpdateEvents(uid: uid) {
                guard !Task.isCancelled, subscriptionKey == expectedKey else { return }
                if case .retry = event {
                    shouldRetry = true
                    continue
                }
                guard case let .value(marker) = event else { continue }
                if let marker {
                    let position = ConvoyMemberPosition(marker: marker)
                    switch ConvoyPositionQuality.judge(
                        position,
                        previous: positions[uid],
                        pending: pendingPositions[uid]
                    ) {
                    case .accept:
                        positions[uid] = position
                        pendingPositions.removeValue(forKey: uid)
                    case .hold:
                        pendingPositions[uid] = position
                        continue
                    case .reject:
                        pendingPositions.removeValue(forKey: uid)
                        continue
                    }
                    if let path = marker.imagePath, imageURLs[path] == nil,
                       ConvoyImageLookupPolicy.shouldAttempt(
                           lastAttempt: imageLookupAttempts[path]
                       ) {
                        resolveImageURL(
                            path: path,
                            repository: repository,
                            subscriptionKey: expectedKey
                        )
                    }
                } else {
                    positions.removeValue(forKey: uid)
                    pendingPositions.removeValue(forKey: uid)
                }
            }
            guard shouldRetry, !Task.isCancelled, subscriptionKey == expectedKey else { return }
            do {
                try await Task.sleep(for: Self.subscriptionRetryDelay)
            } catch {
                return
            }
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

/// Both public-nearby and convoy overlays use the same geographic timeline.
/// Project the resulting coordinates every camera frame; never animate pixels
/// with SwiftUI, which would drift independently of a rotating/moving map.
struct DriverMotionRoster {
    private var tracks: [String: DriverMotionTimeline] = [:]
    private var accepted: [String: ConvoyMemberPosition] = [:]
    private var pending: [String: ConvoyMemberPosition] = [:]

    mutating func update(_ members: [ConvoyMemberPosition], now: TimeInterval, wallNow: Date) {
        let uids = Set(members.map(\.uid))
        tracks = tracks.filter { uids.contains($0.key) }
        accepted = accepted.filter { uids.contains($0.key) }
        pending = pending.filter { uids.contains($0.key) }
        for member in members {
            switch ConvoyPositionQuality.judge(member, previous: accepted[member.uid], pending: pending[member.uid]) {
            case .reject:
                pending.removeValue(forKey: member.uid)
                continue
            case .hold:
                pending[member.uid] = member
                continue
            case .accept:
                pending.removeValue(forKey: member.uid)
            }
            var timeline = tracks[member.uid] ?? DriverMotionTimeline()
            if timeline.update(
                DriverMotionPoint(latitude: member.latitude, longitude: member.longitude, bearing: member.headingDegrees),
                recordedAt: member.updatedAt,
                sampleAge: member.updatedAt.map { wallNow.timeIntervalSince($0) } ?? 0,
                now: now, speed: member.speedMetersPerSecond, course: member.headingDegrees
            ) {
                tracks[member.uid] = timeline
                accepted[member.uid] = member
            }
        }
    }

    mutating func rendered(_ members: [ConvoyMemberPosition], at now: TimeInterval) -> [ConvoyMemberPosition] {
        members.compactMap { member in
            guard var timeline = tracks[member.uid], let point = timeline.frame(at: now) else { return nil }
            tracks[member.uid] = timeline
            return ConvoyMemberPosition(uid: member.uid, latitude: point.latitude, longitude: point.longitude,
                                        displayName: member.displayName, imagePath: member.imagePath,
                                        updatedAt: member.updatedAt, accuracyMeters: member.accuracyMeters,
                                        headingDegrees: member.headingDegrees, speedMetersPerSecond: member.speedMetersPerSecond)
        }
    }

    func isAnimating(at now: TimeInterval) -> Bool { tracks.values.contains { $0.isAnimating(at: now) } }
}

// Same display-space geometry as Android DriverPhotoLayout.kt. Units are points.
struct DriverPhotoPoint: Equatable, Sendable {
    let x: Double; let y: Double
    func distance(to other: Self) -> Double { hypot(x - other.x, y - other.y) }
}
struct DriverPhotoRect: Equatable, Sendable {
    let left: Double; let top: Double; let right: Double; let bottom: Double
    func intersects(_ other: Self) -> Bool { left < other.right && right > other.left && top < other.bottom && bottom > other.top }
    func inflated(_ value: Double) -> Self { Self(left: left - value, top: top - value, right: right + value, bottom: bottom + value) }
    func contains(_ other: Self) -> Bool { other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom }
}
struct DriverPhotoAnchor: Equatable, Sendable { let uid: String; let point: DriverPhotoPoint }
struct DriverPhotoLine: Equatable, Sendable { let start: DriverPhotoPoint; let end: DriverPhotoPoint }
struct DriverPhotoPlacement: Equatable, Sendable, Identifiable {
    let uid: String; let anchor: DriverPhotoPoint; let photo: DriverPhotoPoint
    let lineStart: DriverPhotoPoint; let lineEnd: DriverPhotoPoint
    let showAnchor: Bool; let slot: Int; let lines: [DriverPhotoLine]
    var id: String { uid }
    var hitBounds: DriverPhotoRect { DriverPhotoRect(left: photo.x - 24, top: photo.y - 24, right: photo.x + 24, bottom: photo.y + 24) }
}
struct DriverPhotoLayoutResult {
    let placed: [DriverPhotoPlacement]; let overflow: [DriverPhotoAnchor]
}

/// A private memoization cache, not observable UI state. One instance belongs to
/// the combined nearby/convoy overlay; it never learns new users or locations.
final class DriverPhotoLayout {
    static let photoRadius = 20.0
    static let ownFreeRadius = 28.0
    private var slots: [String: Int] = [:]
    private var expanded = false
    private var expandedCount = 0
    private var nextToken = 1_000
    private var offsets: [String: DriverPhotoPoint] = [:]
    private struct Candidate { let slot: Int; let photo: DriverPhotoPoint }
    private static let directions: [DriverPhotoPoint] = {
        let d = sqrt(0.5)
        return [.init(x: 1, y: 0), .init(x: d, y: d), .init(x: 0, y: 1), .init(x: -d, y: d),
                .init(x: -1, y: 0), .init(x: -d, y: -d), .init(x: 0, y: -1), .init(x: d, y: -d)]
    }()

    func layout(_ anchors: [DriverPhotoAnchor], width: Double, height: Double,
                ownPoint: DriverPhotoPoint?, exclusions: [DriverPhotoRect] = []) -> DriverPhotoLayoutResult {
        var seen = Set<String>()
        let valid = anchors.filter { !$0.uid.isEmpty && $0.point.x.isFinite && $0.point.y.isFinite && seen.insert($0.uid).inserted }
            .sorted { a, b in
                let aOld = slots[a.uid] != nil; let bOld = slots[b.uid] != nil
                return aOld == bOld ? a.uid < b.uid : aOld
            }
        let uids = Set(valid.map(\.uid))
        slots = slots.filter { uids.contains($0.key) }
        offsets = offsets.filter { uids.contains($0.key) }
        if valid.isEmpty { expanded = false }
        var grid: [DriverPhotoPoint] = []
        var y = 32.0
        while y <= height - 32, grid.count < 2_500 {
            var x = 32.0
            while x <= width - 32, grid.count < 2_500 { grid.append(.init(x: x, y: y)); x += 48 }
            y += 48
        }
        // Count/spatial hysteresis matches Android; extra clearance prevents
        // tiny geographic movement toggling between short and wide layouts.
        let spread = valid.indices.allSatisfy { i in valid.dropFirst(i + 1).allSatisfy { valid[i].point.distance(to: $0.point) >= 104 } }
        let canCollapse = valid.count <= max(2, Int(Double(expandedCount) * 0.6)) || spread
        let short = expanded && canCollapse ? plan(valid, width: width, height: height, ownPoint: ownPoint, exclusions: exclusions, grid: grid, wide: false, clearance: 8) : nil
        if short?.overflow.isEmpty == true { expanded = false }
        var result = short.flatMap { $0.overflow.isEmpty ? $0 : nil }
            ?? plan(valid, width: width, height: height, ownPoint: ownPoint, exclusions: exclusions, grid: grid, wide: expanded)
        if !expanded, !result.overflow.isEmpty {
            let wide = plan(valid, width: width, height: height, ownPoint: ownPoint, exclusions: exclusions, grid: grid, wide: true)
            if wide.placed.count > result.placed.count { result = wide; expanded = true; expandedCount = valid.count }
        }
        if expanded, !result.overflow.isEmpty {
            // Moving sticky offsets can fragment available grid capacity.
            let packed = plan(valid, width: width, height: height, ownPoint: ownPoint,
                exclusions: exclusions, grid: grid, wide: true, reusePrior: false)
            if packed.placed.count > result.placed.count { result = packed }
        }
        let committed = result.placed.map { item -> DriverPhotoPlacement in
            guard item.slot < 0 else { return item }
            let token = nextToken; nextToken += 1
            return DriverPhotoPlacement(uid: item.uid, anchor: item.anchor, photo: item.photo,
                lineStart: item.lineStart, lineEnd: item.lineEnd, showAnchor: item.showAnchor, slot: token, lines: item.lines)
        }
        for item in committed {
            slots[item.uid] = item.slot
            offsets[item.uid] = .init(x: item.photo.x - item.anchor.x, y: item.photo.y - item.anchor.y)
        }
        return DriverPhotoLayoutResult(placed: committed, overflow: result.overflow)
    }

    private func plan(_ anchors: [DriverPhotoAnchor], width: Double, height: Double,
                      ownPoint: DriverPhotoPoint?, exclusions: [DriverPhotoRect], grid: [DriverPhotoPoint], wide: Bool, clearance: Double = 4, reusePrior: Bool = true) -> DriverPhotoLayoutResult {
        let viewport = DriverPhotoRect(left: 8, top: 8, right: width - 8, bottom: height - 8)
        var placed: [DriverPhotoPlacement] = []
        var overflow: [DriverPhotoAnchor] = []
        for item in anchors {
            let candidates: [Candidate]
            if wide {
                let ordered = grid.enumerated().sorted { a, b in
                    let ad = a.element.distance(to: item.point); let bd = b.element.distance(to: item.point)
                    return ad == bd ? (a.offset ^ Self.stableHash(item.uid)) < (b.offset ^ Self.stableHash(item.uid)) : ad < bd
                }.map { Candidate(slot: -1 - $0.offset, photo: $0.element) }
                if reusePrior, let slot = slots[item.uid], slot >= 1_000, let offset = offsets[item.uid] {
                    candidates = [Candidate(slot: slot, photo: .init(x: item.point.x + offset.x, y: item.point.y + offset.y))] + ordered
                } else { candidates = ordered }
            } else {
                let first = Self.stableHash(item.uid) % 8
                var choices: [Int] = []
                if let slot = slots[item.uid], slot < 16 { choices.append(slot) }
                for ring in 0...1 { for step in 0...7 { choices.append(ring * 8 + (first + step) % 8) } }
                var used = Set<Int>()
                candidates = choices.filter { used.insert($0).inserted }.map { slot in
                    let vector = Self.directions[slot % 8]; let radius = slot < 8 ? 56.0 : 72.0
                    return Candidate(slot: slot, photo: .init(x: item.point.x + vector.x * radius, y: item.point.y + vector.y * radius))
                }
            }
            var chosen: DriverPhotoPlacement?
            for candidate in candidates {
                let photo = candidate.photo
                let rect = DriverPhotoRect(left: photo.x - 24, top: photo.y - 24, right: photo.x + 24, bottom: photo.y + 24)
                guard viewport.contains(rect), !exclusions.contains(where: { (wide ? $0 : $0.inflated(clearance)).intersects(rect) }),
                      !placed.contains(where: { (wide ? $0.hitBounds : $0.hitBounds.inflated(clearance)).intersects(rect) }),
                      !(ownPoint.map { Self.circleIntersectsRect($0, radius: Self.ownFreeRadius, rect: rect) } ?? false)
                else { continue }
                let dx = photo.x - item.point.x; let dy = photo.y - item.point.y
                let distance = hypot(dx, dy)
                guard distance >= Self.photoRadius + 4 else { continue }
                let end = DriverPhotoPoint(x: photo.x - dx / distance * Self.photoRadius, y: photo.y - dy / distance * Self.photoRadius)
                let insideOwn = ownPoint.map { item.point.distance(to: $0) < Self.ownFreeRadius } ?? false
                if !wide, let ownPoint, !insideOwn, Self.segmentDistance(ownPoint, a: item.point, b: end) < Self.ownFreeRadius { continue }
                let lines = ownPoint.map { Self.clippedLines(item.point, b: end, center: $0, radius: Self.ownFreeRadius) }
                    ?? [DriverPhotoLine(start: item.point, end: end)]
                chosen = DriverPhotoPlacement(uid: item.uid, anchor: item.point, photo: photo,
                    lineStart: lines.last?.start ?? end, lineEnd: end, showAnchor: !insideOwn, slot: candidate.slot, lines: lines)
                break
            }
            if let chosen { placed.append(chosen) } else { overflow.append(item) }
        }
        return DriverPhotoLayoutResult(placed: placed, overflow: overflow)
    }

    static func stableHash(_ uid: String) -> Int {
        var hash: UInt32 = 2_166_136_261
        for byte in uid.utf8 { hash = (hash ^ UInt32(byte)) &* 16_777_619 }
        return Int(hash & 0x7fff_ffff)
    }
    private static func circleIntersectsRect(_ point: DriverPhotoPoint, radius: Double, rect: DriverPhotoRect) -> Bool {
        hypot(min(rect.right, max(rect.left, point.x)) - point.x, min(rect.bottom, max(rect.top, point.y)) - point.y) < radius
    }
    private static func segmentDistance(_ point: DriverPhotoPoint, a: DriverPhotoPoint, b: DriverPhotoPoint) -> Double {
        let dx = b.x - a.x; let dy = b.y - a.y; let length = dx * dx + dy * dy
        let fraction = length > 0 ? min(1, max(0, ((point.x - a.x) * dx + (point.y - a.y) * dy) / length)) : 0
        return point.distance(to: .init(x: a.x + dx * fraction, y: a.y + dy * fraction))
    }
    private static func clippedLines(_ a: DriverPhotoPoint, b: DriverPhotoPoint, center: DriverPhotoPoint, radius: Double) -> [DriverPhotoLine] {
        let dx = b.x - a.x; let dy = b.y - a.y; let x = a.x - center.x; let y = a.y - center.y
        let aa = dx * dx + dy * dy
        guard aa > 0 else { return [] }
        let bb = 2 * (x * dx + y * dy); let cc = x * x + y * y - radius * radius
        let discriminant = bb * bb - 4 * aa * cc
        guard discriminant > 0 else { return [DriverPhotoLine(start: a, end: b)] }
        let enter = (-bb - sqrt(discriminant)) / (2 * aa); let exit = (-bb + sqrt(discriminant)) / (2 * aa)
        guard exit > 0, enter < 1 else { return [DriverPhotoLine(start: a, end: b)] }
        var lines: [DriverPhotoLine] = []
        if enter > 0 { lines.append(.init(start: a, end: .init(x: a.x + dx * enter, y: a.y + dy * enter))) }
        if exit < 1 { lines.append(.init(start: .init(x: a.x + dx * exit, y: a.y + dy * exit), end: b)) }
        return lines
    }
}
