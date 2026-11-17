package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.crownhunt.CrownSpawnMessages
import com.kungsbackacarcommunity.app.roadcameras.LocalRoadCameraLayers
import com.kungsbackacarcommunity.app.roadcameras.cameraLabel
import kotlin.math.roundToInt

private data class DensityEntry(val item: MapDensityItem, val sourceId: String,
    val crown: Boolean, val icon: Int, val color: Int, val glyphColor: Int, val label: String)

private data class DensityVisual(val group: MapDensityGroup, val entry: DensityEntry,
    val anchor: MapDensityScreenPoint, val placed: MapDensityScreenPoint, val slot: Int)

/** Static groups only. Driver avatars and their layout belong to their existing overlays. */
@Composable
internal fun MapDensityOverlay(surface: MapSurface, incidents: List<MapIncidentMarker>, covered: Boolean = false) {
    val crowns by surface.crownMarkers.collectAsState()
    val camera by surface.cameraSnapshot.collectAsState()
    val selectedIncident by surface.incidentTap.collectAsState()
    val selectedCrown by surface.crownTap.collectAsState()
    val ownPoint by surface.renderedOwnPoint.collectAsState()
    val zoomState = remember { MapDensityZoom() }
    val bucket = zoomState.update(camera?.zoom ?: 14.0)
    val roadCameras = LocalRoadCameraLayers.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val entries = remember(incidents, crowns, selectedIncident, selectedCrown, configuration, resources, roadCameras?.cameras) {
        fun text(id: Int, vararg args: Any): String = resources.getString(id, *args)
        incidents.map { marker ->
        val critical = marker.iconRes in setOf(R.drawable.ic_incident_accident,
            R.drawable.ic_incident_hazard, R.drawable.ic_incident_road_closed)
        val roadCamera = roadCameras?.selected(marker.id)
        val label = if (roadCamera != null) text(cameraLabel(roadCamera.type)) + " · " + roadCamera.name
        else text(when (marker.iconRes) {
            R.drawable.ic_incident_accident -> R.string.incidents_typeAccident
            R.drawable.ic_incident_roadwork -> R.string.incidents_typeRoadwork
            R.drawable.ic_incident_hazard -> R.string.incidents_typeHazard
            R.drawable.ic_incident_road_closed -> R.string.incidents_typeRoadClosed
            R.drawable.ic_incident_police -> R.string.incidents_typePolice
            else -> R.string.incidents_markerLabel
        })
        DensityEntry(MapDensityItem("incident:" + marker.id, marker.latitude, marker.longitude,
            marker.groupCount, critical, selectedIncident == marker.id), marker.id, false,
            marker.iconRes, marker.colorArgb, marker.glyphColorArgb, label)
    } + crowns.filterNot { it.collectedByYou }.map { marker ->
        val rarity = marker.rarity?.let { text(CrownSpawnMessages.rarityLabelRes(it)) }
        val label = (rarity ?: text(R.string.crownHunt_popupTitle)) +
            (marker.rewardPoints?.let { " · " + text(R.string.crownHunt_spawnReward, it) } ?: "")
        DensityEntry(MapDensityItem("crown:" + marker.id, marker.latitude, marker.longitude,
            selected = selectedCrown == marker.id), marker.id, true, marker.iconRes,
            marker.discColorArgb, marker.glyphColorArgb, label)
    }
    }
    val items = remember(entries) { entries.map { it.item } }
    val groups = remember(items, bucket) { MapDensityPolicy.groups(items, bucket) }
    val byId = remember(entries) { entries.associateBy { it.item.id } }
    val compactGroups = groups.filter { it.count > 1 }
    val masked = compactGroups.flatMap { it.members }.mapNotNull { byId[it.id] }
    val hiddenIncidents = masked.filterNot { it.crown }.map { it.sourceId }.toSet()
    val hiddenCrowns = masked.filter { it.crown }.map { it.sourceId }.toSet() + crowns.filter { it.collectedByYou }.map { it.id }
    LaunchedEffect(surface, incidents, hiddenIncidents, hiddenCrowns) {
        surface.setGroupedMarkerIds(hiddenIncidents, hiddenCrowns)
        surface.setIncidentMarkers(incidents)
    }
    DisposableEffect(surface) {
        onDispose { surface.setGroupedMarkerIds(emptySet(), emptySet()) }
    }
    var selection by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(covered) { if (covered) selection = null }
    val currentSelection = selection?.mapNotNull { byId[it] }.orEmpty()
    var page by remember(selection) { mutableIntStateOf(0) }
    val pageCount = ((currentSelection.size + 19) / 20).coerceAtLeast(1)
    val safePage = page.coerceIn(0, pageCount - 1)
    val pageStart = safePage * 20
    val pageEntries = currentSelection.drop(pageStart).take(20)
    LaunchedEffect(currentSelection.isEmpty()) { if (currentSelection.isEmpty()) selection = null }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val density = LocalDensity.current.density
        val sidePx = 48f * density
        val own = ownPoint?.let { surface.screenPositionFor(it.latitude, it.longitude) }?.takeIf { it.trustworthy }
        val visuals = compactGroups.mapNotNull { group ->
            val entry = byId[group.anchor.id] ?: return@mapNotNull null
            val point = surface.screenPositionFor(group.anchor.latitude, group.anchor.longitude)
                ?.takeIf { it.trustworthy && it.x >= -sidePx && it.y >= -sidePx && it.x <= widthPx + sidePx && it.y <= heightPx + sidePx }
                ?: return@mapNotNull null
            val anchor = MapDensityScreenPoint(point.x / density.toDouble(), point.y / density.toDouble())
            val ownScreen = own?.let { MapDensityScreenPoint(it.x / density.toDouble(), it.y / density.toDouble()) }
            val placed = MapDensityPolicy.badgePosition(anchor, ownScreen, widthPx / density.toDouble(), heightPx / density.toDouble())
            val slot = if (placed == anchor || ownScreen == null) 0 else 1 + ((kotlin.math.atan2(placed.y-ownScreen.y, placed.x-ownScreen.x) * 180 / Math.PI + 360) / 45).roundToInt() % 8
            group.id to DensityVisual(group, entry, anchor, placed, slot)
        }.toMap()
        val presence = rememberMapPresence(visuals, active = !covered, placements = visuals.mapValues { it.value.slot }, retainRemovedPublicItems = true)
        presence.forEach { present ->
            key(present.id) {
                val (group, entry, anchor, placed) = present.value
                val point = Offset((anchor.x * density).toFloat(), (anchor.y * density).toFloat())
                val description = stringResource(R.string.mapDensity_groupLabel, group.count) +
                    (if (group.critical) ". " + stringResource(R.string.mapDensity_hazardsIncluded) else "") +
                    (if (group.selected) ". " + stringResource(R.string.mapDensity_selected) else "")
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = present.alpha }) {
                if (placed != anchor) Canvas(Modifier.fillMaxSize()) {
                    drawLine(Color.White.copy(alpha = 0.8f), point,
                        Offset((placed.x * density).toFloat(), (placed.y * density).toFloat()), 2.dp.toPx())
                    drawCircle(Color.White, 2.dp.toPx(), point)
                }
                Box(Modifier.offset { IntOffset((placed.x * density - sidePx / 2).roundToInt(), (placed.y * density - sidePx / 2).roundToInt()) }
                    .size(48.dp).semantics { contentDescription = description }
                    .clickable(enabled = present.interactive, role = Role.Button) { selection = group.members.map { it.id } }
                    .then(if (present.interactive) Modifier else Modifier.clearAndSetSemantics {})
                    .testTag("map_density_group_${group.id}")) {
                    Box(Modifier.align(Alignment.Center).size(34.dp)
                        .background(Color(entry.color), CircleShape)
                        .border(if (group.selected) 3.dp else 1.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(entry.icon), null, Modifier.size(20.dp), tint = Color(entry.glyphColor))
                    }
                    Surface(Modifier.align(Alignment.TopEnd), shape = CircleShape, color = Color(0xFF111827), contentColor = Color.White) {
                        Text(group.compactCount, Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    if (group.critical) Icon(Icons.Default.Warning, null, Modifier.align(Alignment.BottomStart).size(16.dp)
                        .background(Color(0xFF111827), CircleShape), tint = Color(0xFFFFD166))
                    if (group.selected) Icon(Icons.Default.Check, null, Modifier.align(Alignment.BottomEnd).size(14.dp)
                        .background(Color(0xFF111827), CircleShape), tint = Color.White)
                }
            }
        }
    }
    }

    if (selection != null && currentSelection.isNotEmpty()) {
        MapDetailSheet(title = stringResource(R.string.mapDensity_title), onDismiss = { selection = null },
            modifier = Modifier.testTag("map_density_details")) {
            Text(stringResource(R.string.mapDensity_choose))
            if (pageCount > 1) Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mapDensity_page, pageStart + 1, pageStart + pageEntries.size, currentSelection.size))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { page = safePage - 1 }, enabled = safePage > 0) { Text(stringResource(R.string.mapDensity_previous)) }
                    TextButton(onClick = { page = safePage + 1 }, enabled = safePage + 1 < pageCount) { Text(stringResource(R.string.mapDensity_next)) }
                }
            }
            pageEntries.forEach { entry ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("map_density_item_${entry.item.id}").clickable(role = Role.Button) {
                    selection = null
                    if (entry.crown) surface.emitCrownTap(entry.sourceId) else surface.emitIncidentTap(entry.sourceId)
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(entry.icon), null, Modifier.size(28.dp), tint = Color(entry.color))
                    Column(Modifier.weight(1f)) {
                        Text(entry.label)
                        if (entry.item.count > 1) Text(stringResource(R.string.mapDensity_groupLabel, entry.item.count), style = MaterialTheme.typography.bodySmall)
                    }
                    if (entry.item.critical) Icon(Icons.Default.Warning, stringResource(R.string.mapDensity_hazardsIncluded))
                    if (entry.item.selected) Icon(Icons.Default.Check, stringResource(R.string.mapDensity_selected))
                }
                HorizontalDivider()
            }
        }
    }
}
