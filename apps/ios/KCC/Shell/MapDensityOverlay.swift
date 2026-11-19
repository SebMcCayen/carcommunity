import SwiftUI

struct MapDensityContentItem: Identifiable, Equatable {
    let item: MapDensityItem
    let label: String
    var incident: MapIncidentMarker?
    var camera: RoadCameraMapGroup?
    var crown: MapCrownMarker?
    var id: String { item.id }
}

/// One presentation pass across static types. Driver overlays remain independent.
struct MapDensityOverlay: View {
    let surface: any MapSurface
    let incidents: IncidentMapCoordinator?
    let cameras: RoadCameraMapCoordinator?
    let crowns: CrownHuntMapCoordinator?
    let onSelect: (MapDensityContentItem) -> Void
    let onGroup: ([MapDensityContentItem]) -> Void
    @State private var zoom = MapDensityZoom()
    @State private var bucket = 14
    @State private var groups: [MapDensityGroup] = []

    private struct Visual: Equatable {
        let group: MapDensityGroup
        let entry: MapDensityContentItem
        let projected: MapScreenPoint
        let position: MapDensityScreenPoint
        let slot: Int
    }
    private struct Input: Equatable { let items: [MapDensityItem]; let bucket: Int }

    var body: some View {
        GeometryReader { geometry in
            TimelineView(.periodic(from: .now, by: 1)) { timeline in
                let entries = entries(at: timeline.date)
                let lookup = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
                let input = Input(items: entries.map(\.item), bucket: bucket)
                let visuals = visuals(lookup: lookup, size: geometry.size)
                MapPresenceLayer(items: visuals, placements: visuals.mapValues(\.slot), retainRemovedPublicItems: true, appliesOpacity: false) { present in
                    let value = present.value
                    ZStack {
                        marker(value.group, entry: value.entry, lookup: lookup, projected: value.projected,
                            position: value.position, opacity: present.alpha)
                    }
                }
                .onChange(of: input, initial: true) { _, value in
                    groups = MapDensityPolicy.groups(value.items, zoomBucket: value.bucket)
                }
            }
        }
        .onChange(of: surface.cameraSnapshot?.zoom, initial: true) { _, value in
            bucket = zoom.update(value ?? 14)
        }
    }

    private func visuals(lookup: [String: MapDensityContentItem], size: CGSize) -> [String: Visual] {
        let own = surface.renderedOwnPoint.flatMap {
            surface.screenPositionFor(latitude: $0.latitude, longitude: $0.longitude)
        }.flatMap { $0.trustworthy ? MapDensityScreenPoint(x: $0.x, y: $0.y) : nil }
        return Dictionary(uniqueKeysWithValues: groups.compactMap { group in
            guard let entry = lookup[group.anchor.id],
                let projected = surface.screenPositionFor(latitude: group.anchor.latitude, longitude: group.anchor.longitude),
                projected.trustworthy, projected.x >= -48, projected.y >= -48,
                projected.x <= Double(size.width + 48), projected.y <= Double(size.height + 48) else { return nil }
            let anchor = MapDensityScreenPoint(x: projected.x, y: projected.y)
            let position = group.count > 1 ? MapDensityPolicy.badgePosition(anchor: anchor, own: own,
                width: Double(size.width), height: Double(size.height)) : anchor
            let slot: Int
            if position != anchor, let own {
                slot = 1 + Int(((atan2(position.y-own.y, position.x-own.x) * 180 / .pi + 360) / 45).rounded()) % 8
            } else { slot = 0 }
            return (group.id, Visual(group: group, entry: entry, projected: projected, position: position, slot: slot))
        })
    }

    @ViewBuilder private func marker(_ group: MapDensityGroup, entry: MapDensityContentItem,
        lookup: [String: MapDensityContentItem], projected: MapScreenPoint, position: MapDensityScreenPoint, opacity: Double) -> some View {
        let anchor = MapDensityScreenPoint(x: projected.x, y: projected.y)
        if position != anchor {
            Path { path in
                path.move(to: CGPoint(x: anchor.x, y: anchor.y))
                path.addLine(to: CGPoint(x: position.x, y: position.y))
            }.stroke(.white.opacity(0.8 * opacity), lineWidth: 2).allowsHitTesting(false)
            Circle().fill(.white.opacity(opacity)).frame(width: 4, height: 4).position(x: anchor.x, y: anchor.y)
                .allowsHitTesting(false)
        }
        Button {
            let current = group.members.compactMap { lookup[$0.id] }
            if group.count > 1 { onGroup(current) } else { onSelect(entry) }
        } label: {
            if group.count > 1 { MapDensityBadge(group: group, entry: entry, mapMode: surface.mapMode) }
            else { MapDensityGlyph(entry: entry, mapMode: surface.mapMode) }
        }
        .buttonStyle(.plain)
        .frame(width: 48, height: 48).contentShape(Rectangle())
        .accessibilityLabel(group.count > 1 ? groupLabel(group) : entry.label)
        .accessibilityIdentifier("map.density." + group.id)
        .opacity(opacity).position(x: position.x, y: position.y)
    }

    private func groupLabel(_ group: MapDensityGroup) -> String {
        var label = String(format: NSLocalizedString("mapDensity.groupLabel", comment: ""), group.count)
        if group.critical { label += ". " + NSLocalizedString("mapDensity.hazardsIncluded", comment: "") }
        if group.selected { label += ". " + NSLocalizedString("mapDensity.selected", comment: "") }
        return label
    }

    private func entries(at now: Date) -> [MapDensityContentItem] {
        var result: [MapDensityContentItem] = []
        if let incidents {
            for marker in IncidentLayerPresentation.markers(enabled: incidents.trafficAlertsEnabled,
                incidents: incidents.visibleIncidents, policeReports: incidents.policeReports) {
                let critical = [IncidentType.accident.symbolName, IncidentType.hazard.symbolName,
                    IncidentType.roadClosed.symbolName].contains(marker.iconName)
                let selected = incidents.selectedIncident?.id == marker.id || incidents.selectedPolice.map { PoliceMapMarker.prefix + $0.id == marker.id } == true
                let item = MapDensityItem(id: "incident:" + marker.id, latitude: marker.latitude,
                    longitude: marker.longitude, critical: critical, selected: selected)
                result.append(MapDensityContentItem(item: item, label: IncidentAccessibility.markerLabel(id: marker.id,
                    incidents: incidents.visibleIncidents, policeReports: incidents.policeReports), incident: marker))
            }
        }
        if let cameras {
            for group in cameras.visibleGroups(center: surface.cameraSnapshot, radius: surface.visibleRadiusMeters(), coordinatedDensity: true) {
                let camera = group.camera
                let item = MapDensityItem(id: "incident:road-camera:" + camera.id,
                    latitude: camera.latitude, longitude: camera.longitude, count: group.views.count,
                    selected: group.views.contains { $0.id == cameras.selectedCamera?.id })
                result.append(MapDensityContentItem(item: item,
                    label: NSLocalizedString(camera.type.titleKey, comment: "") + " · " + camera.name, camera: group))
            }
        }
        if let crowns {
            let spawns = Dictionary(uniqueKeysWithValues: crowns.spawns.map { ("spawn:" + $0.id, $0) })
            let points = Dictionary(uniqueKeysWithValues: crowns.points.map { ("point:" + $0.id, $0) })
            for marker in crowns.markers(at: now) where !marker.collectedByYou {
                let rawID = marker.id.hasPrefix("spawn:") || marker.id.hasPrefix("point:") ? String(marker.id.dropFirst(6)) : marker.id
                let item = MapDensityItem(id: "crown:" + rawID, latitude: marker.latitude,
                    longitude: marker.longitude, selected: crowns.selectedTarget?.id == marker.id)
                let label: String
                if let spawn = spawns[marker.id] {
                    label = CrownHuntStrings.rarityName(spawn.rarity) + " · " + String(format: NSLocalizedString("crownHunt.spawnReward", comment: ""), spawn.rewardPoints)
                } else { label = points[marker.id]?.title ?? NSLocalizedString("crownHunt.popupTitle", comment: "") }
                result.append(MapDensityContentItem(item: item, label: label, crown: marker))
            }
        }
        return result
    }
}

private struct MapDensityGlyph: View {
    let entry: MapDensityContentItem
    var mapMode: MapMode = .night
    @ViewBuilder var body: some View {
        if let marker = entry.incident { IncidentMarkerBadge(marker: marker) }
        else if let camera = entry.camera { IncidentMarkerBadge(marker: camera.camera.marker) }
        else if let crown = entry.crown {
            CrownMarkerTile(rank: crown.visualRank, inRange: crown.isInRange,
                mapMode: mapMode, isCuratedPoint: crown.id.hasPrefix("point:"))
        }
    }
}

private struct MapDensityBadge: View {
    let group: MapDensityGroup
    let entry: MapDensityContentItem
    let mapMode: MapMode
    var body: some View {
        ZStack {
            MapDensityGlyph(entry: entry, mapMode: mapMode).frame(width: 34, height: 34)
                .overlay { Circle().strokeBorder(.white, lineWidth: group.selected ? 3 : 1) }
            Text(group.compactCount).font(.system(size: 11, weight: .semibold)).monospacedDigit()
                .foregroundStyle(.white).padding(.horizontal, 4).padding(.vertical, 1)
                .background(Color(red: 17/255, green: 24/255, blue: 39/255), in: Capsule())
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            if group.critical {
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 14)).foregroundStyle(.yellow)
                    .background(.black, in: Circle()).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            }
            if group.selected {
                Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)).foregroundStyle(.white)
                    .background(.black, in: Circle()).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
        }.frame(width: 48, height: 48).accessibilityHidden(true)
    }
}

struct MapDensityGroupContents: View {
    let entries: [MapDensityContentItem]
    let mapMode: MapMode
    let onSelect: (MapDensityContentItem) -> Void
    @State private var page = 0
    private var pageCount: Int { max(1, (entries.count + 19) / 20) }
    private var safePage: Int { min(max(0, page), pageCount - 1) }
    private var pageEntries: [MapDensityContentItem] { Array(entries.dropFirst(safePage * 20).prefix(20)) }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("mapDensity.choose")
            if pageCount > 1 {
                Text(String(format: NSLocalizedString("mapDensity.page", comment: ""), safePage * 20 + 1, safePage * 20 + pageEntries.count, entries.count)).font(.caption)
                HStack {
                    Button("mapDensity.previous") { page = safePage - 1 }.disabled(safePage == 0)
                    Spacer()
                    Button("mapDensity.next") { page = safePage + 1 }.disabled(safePage + 1 >= pageCount)
                }
            }
            ForEach(pageEntries) { entry in
                Button { onSelect(entry) } label: {
                    HStack(spacing: 12) {
                        MapDensityGlyph(entry: entry, mapMode: mapMode).frame(width: 34, height: 34)
                        VStack(alignment: .leading) {
                            Text(entry.label).frame(maxWidth: .infinity, alignment: .leading)
                            if entry.item.count > 1 { Text(String(format: NSLocalizedString("mapDensity.groupLabel", comment: ""), entry.item.count)).font(.caption) }
                        }
                        if entry.item.critical { Image(systemName: "exclamationmark.triangle.fill").accessibilityLabel(Text("mapDensity.hazardsIncluded")) }
                        if entry.item.selected { Image(systemName: "checkmark").accessibilityLabel(Text("mapDensity.selected")) }
                    }.frame(minHeight: 56).contentShape(Rectangle())
                }.buttonStyle(.plain)
                Divider()
            }
        }
    }
}
