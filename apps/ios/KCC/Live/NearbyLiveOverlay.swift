import SwiftUI

/// On-screen-only public live sharers. Convoy members use the separate green
/// awareness layer (including edge arrows); nearby sharers use a cyan ring
/// and disappear outside the viewport to keep the map quiet and privacy-minded.
struct NearbyLiveOverlay: View {
    @Bindable var coordinator: NearbyLiveCoordinator
    let projection: any MapProjection
    let onSelect: (String) -> Void
    var clubBadges: LiveClubBadgeCoordinator? = nil
    var badgeViewerID: String? = nil
    var onDrawnUIDsChanged: (Set<String>) -> Void = { _ in }
    @State private var motion = DriverMotionRoster()
    @State private var presented: [ConvoyMemberPosition] = []

    var body: some View {
        GeometryReader { geometry in
            TimelineView(.periodic(from: .now, by: 30)) { context in
                let placements = visible(size: geometry.size, now: context.date)
                ZStack {
                    ForEach(placements) { item in
                        Button {
                            onSelect(item.marker.uid)
                        } label: {
                            markerChip(item.marker)
                        }
                        .buttonStyle(.plain)
                        .frame(minWidth: 48, minHeight: 48)
                        .contentShape(Rectangle())
                        .accessibilityLabel([String.localizedStringWithFormat(
                            NSLocalizedString("nearby.liveSharerOnMap", comment: "Nearby live sharer"),
                            spokenName(item.marker)
                        ), clubBadge(item.marker)?.accessibilityLabel].compactMap { $0 }.joined(separator: ", "))
                        .position(x: item.point.x, y: item.point.y)
                    }
                }
                .onChange(of: Set(placements.map(\.id)), initial: true) { _, uids in
                    onDrawnUIDsChanged(uids)
                }
            }
        }
        .task(id: coordinator.positions) {
            let now = ProcessInfo.processInfo.systemUptime
            motion.update(coordinator.visibleMarkers().map(ConvoyMemberPosition.init(marker:)), now: now, wallNow: Date())
            presented = motion.rendered(coordinator.visibleMarkers().map(ConvoyMemberPosition.init(marker:)), at: now)
            while motion.isAnimating(at: ProcessInfo.processInfo.systemUptime), !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(DriverMotionPolicy.frameInterval)) } catch { return }
                presented = motion.rendered(coordinator.visibleMarkers().map(ConvoyMemberPosition.init(marker:)), at: ProcessInfo.processInfo.systemUptime)
            }
        }
        .onDisappear { onDrawnUIDsChanged([]) }
        .accessibilityElement(children: .contain)
    }

    private func visible(size: CGSize, now: Date) -> [NearbyLivePlacement] {
        guard projection.cameraSnapshot != nil, size.width > 0, size.height > 0 else { return [] }
        let margin = 24.0
        let originals = coordinator.visibleMarkers(at: now)
        let rendered = presented
        let byUID = Dictionary(uniqueKeysWithValues: originals.map { ($0.uid, $0) })
        return rendered.compactMap { displayed in
            guard let marker = byUID[displayed.uid], let point = projection.screenPositionFor(
                latitude: displayed.latitude,
                longitude: displayed.longitude
            ), point.trustworthy,
            point.x >= margin, point.y >= margin,
            point.x <= Double(size.width) - margin,
            point.y <= Double(size.height) - margin
            else { return nil }
            return NearbyLivePlacement(
                marker: marker,
                point: CGPoint(x: CGFloat(point.x), y: CGFloat(point.y))
            )
        }
    }

    private func markerChip(_ marker: LiveMarker) -> some View {
        let url = marker.imagePath.flatMap { coordinator.imageURLs[$0] }
        return LiveCarPhotoBadge(imageURL: url, badge: clubBadge(marker))
        .shadow(radius: 2, y: 1)
    }

    private func clubBadge(_ marker: LiveMarker) -> LiveClubBadge? {
        clubBadges?.badge(for: marker.uid, viewerID: badgeViewerID)
    }

    private func spokenName(_ marker: LiveMarker) -> String {
        marker.displayName ?? String(localized: "nearby.unknownSharer")
    }
}

private struct NearbyLivePlacement: Identifiable {
    let marker: LiveMarker
    let point: CGPoint
    var id: String { marker.uid }
}

/// Re-resolve from the authorized, freshness-filtered roster every render/tick.
/// Keeping only the uid in shell selection cannot retain a stopped/blocked user's
/// old snapshot while their marker has already disappeared from the map.
struct NearbySharerDetail: View {
    @Bindable var coordinator: NearbyLiveCoordinator
    let uid: String
    let onUnavailable: () -> Void
    var onViewProfile: ((String, String?) -> Void)? = nil
    var clubsCoordinator: ClubsCoordinator? = nil
    var onViewClub: ((String) -> Void)? = nil

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let marker = coordinator.visibleMarkers(at: context.date).first { $0.uid == uid }
            Group {
                if let marker {
                    NearbySharerIdentity(
                        marker: marker,
                        imageURL: marker.imagePath.flatMap { coordinator.imageURLs[$0] },
                        clubsCoordinator: clubsCoordinator,
                        onVisitClub: onViewClub.map { action in { clubID in
                            guard coordinator.profileTarget(uid: uid) != nil else { onUnavailable(); return }
                            action(clubID)
                        } },
                        onVisitProfile: onViewProfile.map { action in {
                            // Re-check the authorized, fresh roster at the tap,
                            // not only when this card was rendered.
                            guard let current = coordinator.profileTarget(uid: uid) else {
                                onUnavailable(); return
                            }
                            action(current.uid, current.displayName)
                        } }
                    )
                }
            }
            .onChange(of: marker == nil, initial: true) { _, unavailable in
                if unavailable { onUnavailable() }
            }
        }
    }
}

struct NearbySharerIdentity: View {
    let marker: LiveMarker
    let imageURL: URL?
    var clubsCoordinator: ClubsCoordinator? = nil
    var onVisitClub: ((String) -> Void)? = nil
    var onVisitProfile: (() -> Void)? = nil
    @State private var affiliation: (memberID: String, clubID: String)?

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            if let imageURL {
                AsyncImage(url: imageURL) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() }
                    else { Image(systemName: "car.side.fill").foregroundStyle(KccTheme.driver) }
                }
                .frame(width: 64, height: 64).clipShape(Circle())
                .overlay(Circle().strokeBorder(KccTheme.driver, lineWidth: 2).allowsHitTesting(false))
                .accessibilityHidden(true)
            } else {
                Image(systemName: "car.side.fill").font(.title2)
                    .foregroundStyle(KccTheme.driver)
                    .frame(width: 64, height: 64)
                    .background(KccTheme.subtle, in: Circle())
                    .accessibilityHidden(true)
            }
            Text(marker.displayName ?? String(localized: "nearby.unknownSharer"))
                .font(.title3.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
            if let recordedAt = marker.recordedAt {
                Text("\(String(localized: "liveLocation.lastUpdated")): \(recordedAt.formatted(.relative(presentation: .named)))")
                    .font(.subheadline).foregroundStyle(KccTheme.secondary)
            }
            if let onVisitProfile {
                Button(action: onVisitProfile) {
                    Text("nearby.visitProfile").frame(maxWidth: .infinity, minHeight: 48)
                }
                .kccPrimaryAction()
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .accessibilityIdentifier("nearby.visitProfile")
            }
            if clubsCoordinator?.enabled == true, let affiliation, affiliation.memberID == marker.uid,
               let onVisitClub {
                Button { onVisitClub(affiliation.clubID) } label: {
                    Text("nearby.visitClub").frame(maxWidth: .infinity, minHeight: 48)
                }
                .kccPrimaryAction()
                .accessibilityIdentifier("nearby.visitClub")
            }
        }
        .accessibilityElement(children: .contain)
        .task(id: "\(marker.uid):\(clubsCoordinator?.enabled == true)") {
            affiliation = nil
            guard let clubsCoordinator, clubsCoordinator.enabled else { return }
            for await clubID in clubsCoordinator.affiliationUpdates(memberID: marker.uid) {
                guard !Task.isCancelled else { return }
                affiliation = clubID.map { (marker.uid, $0) }
            }
        }
    }
}

/// The selected photo + true-position-dot + leader design. One roster and one
/// layout serve nearby and convoy drivers, including dense longer-line cases.
struct LiveDriverMapOverlay: View {
    let convoy: [ConvoyMemberPosition]
    let nearby: [LiveMarker]
    let convoyImageURLs: [String: URL]
    let nearbyImageURLs: [String: URL]
    let projection: any MapProjection
    let onSelect: (String, Bool) -> Void
    var clubBadges: LiveClubBadgeCoordinator? = nil
    var badgeViewerID: String? = nil
    var controlFrames: [CGRect] = [] // Global UIKit coordinates, measured from actual controls.
    var onConvoyDrawn: (Set<String>) -> Void = { _ in }
    var onNearbyDrawn: (Set<String>) -> Void = { _ in }
    @State private var motion = DriverMotionRoster()
    @State private var presented: [ConvoyMemberPosition] = []
    @State private var layout = DriverPhotoLayout()
    @State private var arrowUIDs = Set<String>()

    private var convoyUIDs: Set<String> { Set(convoy.map(\.uid)) }
    private var roster: [ConvoyMemberPosition] {
        var seen = Set<String>()
        return (convoy + nearby.filter { !convoyUIDs.contains($0.uid) }.map(ConvoyMemberPosition.init(marker:)))
            .filter { seen.insert($0.uid).inserted }
    }

    var body: some View {
        GeometryReader { geometry in
            let originals = Dictionary(uniqueKeysWithValues: roster.map { ($0.uid, $0) })
            let visible = presented.filter { originals[$0.uid] != nil }
            let anchors = visible.compactMap { member -> DriverPhotoAnchor? in
                guard projection.cameraSnapshot != nil,
                      let point = projection.screenPositionFor(latitude: member.latitude, longitude: member.longitude),
                      point.trustworthy, point.x >= 0, point.y >= 0,
                      point.x <= Double(geometry.size.width), point.y <= Double(geometry.size.height) else { return nil }
                return DriverPhotoAnchor(uid: member.uid, point: .init(x: point.x, y: point.y))
            }
            let own = projection.renderedOwnPoint.flatMap {
                projection.screenPositionFor(latitude: $0.latitude, longitude: $0.longitude)
            }.flatMap { $0.trustworthy ? DriverPhotoPoint(x: $0.x, y: $0.y) : nil }
            let origin = geometry.frame(in: .global)
            let exclusions = controlFrames.filter { $0.width > 0 && $0.height > 0 }.map {
                DriverPhotoRect(left: Double($0.minX - origin.minX), top: Double($0.minY - origin.minY),
                                right: Double($0.maxX - origin.minX), bottom: Double($0.maxY - origin.minY))
            }
            let plan = layout.layout(anchors, width: Double(geometry.size.width), height: Double(geometry.size.height), ownPoint: own, exclusions: exclusions)
            let photoUIDs = Set(plan.placed.map(\.uid))
            ZStack {
                ConvoyMapAwarenessOverlay(members: visible.filter { convoyUIDs.contains($0.uid) },
                    imageURLs: convoyImageURLs, projection: projection,
                    clubBadges: clubBadges, badgeViewerID: badgeViewerID,
                    onDrawnUIDsChanged: { arrowUIDs = $0 }, presentationProvided: true,
                    showsOnScreenPhotos: false, suppressedArrowUIDs: Set(anchors.map(\.uid)))
                    // This child only draws offscreen arrows. Photo Buttons
                    // below own selection; empty map space belongs to Mapbox.
                    .allowsHitTesting(false)
                Canvas { context, _ in
                    for item in plan.placed {
                        for segment in item.lines {
                            var path = Path()
                            path.move(to: CGPoint(x: segment.start.x, y: segment.start.y))
                            path.addLine(to: CGPoint(x: segment.end.x, y: segment.end.y))
                            context.stroke(path, with: .color(KccTheme.text), style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                        }
                        if item.showAnchor {
                            let rect = CGRect(x: item.anchor.x - 3, y: item.anchor.y - 3, width: 6, height: 6)
                            context.fill(Path(ellipseIn: rect), with: .color(KccTheme.driver))
                            context.stroke(Path(ellipseIn: rect), with: .color(KccTheme.text), lineWidth: 1)
                        }
                    }
                }.allowsHitTesting(false).accessibilityHidden(true)
                ForEach(plan.placed) { item in
                    if let member = originals[item.uid] {
                        Button { onSelect(item.uid, convoyUIDs.contains(item.uid)) } label: {
                            LiveCarPhotoBadge(imageURL: member.imagePath.flatMap {
                                convoyUIDs.contains(item.uid) ? convoyImageURLs[$0] : nearbyImageURLs[$0]
                            }, badge: clubBadges?.badge(for: item.uid, viewerID: badgeViewerID),
                                showMembershipMapRing: clubBadges?.showsMembershipRing(for: item.uid, viewerID: badgeViewerID) == true)
                                .frame(width: 48, height: 48).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(spokenLabel(member))
                        .accessibilityIdentifier("driver.photo.\(item.uid)")
                        .position(x: item.photo.x, y: item.photo.y)
                    }
                }
            }
            .onChange(of: [photoUIDs, convoyUIDs, arrowUIDs], initial: true) { _, _ in
                onConvoyDrawn(photoUIDs.intersection(convoyUIDs).union(arrowUIDs).intersection(Set(originals.keys)))
                onNearbyDrawn(photoUIDs.subtracting(convoyUIDs).intersection(Set(originals.keys)))
            }
        }
        .task(id: roster) {
            let now = ProcessInfo.processInfo.systemUptime
            motion.update(roster, now: now, wallNow: Date())
            presented = motion.rendered(roster, at: now)
            while motion.isAnimating(at: ProcessInfo.processInfo.systemUptime), !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(DriverMotionPolicy.frameInterval)) } catch { return }
                presented = motion.rendered(roster, at: ProcessInfo.processInfo.systemUptime)
            }
        }
        .onDisappear { onConvoyDrawn([]); onNearbyDrawn([]) }
    }

    private func spokenLabel(_ member: ConvoyMemberPosition) -> String {
        let name = member.displayName ?? String(localized: "nearby.unknownSharer")
        let key = convoyUIDs.contains(member.uid) ? "convoy.awarenessMemberOnMap" : "nearby.liveSharerOnMap"
        var parts = [String.localizedStringWithFormat(NSLocalizedString(key, comment: "Driver photo on map"), name)]
        if let badge = clubBadges?.badge(for: member.uid, viewerID: badgeViewerID) { parts.append(badge.accessibilityLabel) }
        if clubBadges?.showsMembershipRing(for: member.uid, viewerID: badgeViewerID) == true {
            parts.append(String(localized: "liveDriverPhoto.membershipRing"))
        }
        return parts.joined(separator: ", ")
    }
}
