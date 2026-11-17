package com.kungsbackacarcommunity.app.shell

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
import androidx.compose.ui.graphics.Color
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
import com.kungsbackacarcommunity.app.roadcameras.ROAD_CAMERA_MARKER_PREFIX
import kotlin.math.roundToInt

private data class DensityEntry(val item: MapDensityItem, val sourceId: String,
    val crown: Boolean, val icon: Int, val color: Int, val glyphColor: Int, val label: String)

/** Static groups only. Driver avatars and their layout belong to their existing overlays. */
@Composable
internal fun MapDensityOverlay(surface: MapSurface, incidents: List<MapIncidentMarker>) {
    val crowns by surface.crownMarkers.collectAsState()
    val camera by surface.cameraSnapshot.collectAsState()
    val selectedIncident by surface.incidentTap.collectAsState()
    val selectedCrown by surface.crownTap.collectAsState()
    val zoomState = remember { MapDensityZoom() }
    val bucket = zoomState.update(camera?.zoom ?: 14.0)
    val roadCameras = LocalRoadCameraLayers.current
    val entries = incidents.map { marker ->
        val critical = marker.iconRes in setOf(R.drawable.ic_incident_accident,
            R.drawable.ic_incident_hazard, R.drawable.ic_incident_road_closed)
        val roadCamera = roadCameras?.selected(marker.id)
        val label = if (roadCamera != null) stringResource(cameraLabel(roadCamera.type)) + " · " + roadCamera.name
        else stringResource(when (marker.iconRes) {
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
        val rarity = marker.rarity?.let { stringResource(CrownSpawnMessages.rarityLabelRes(it)) }
        val label = (rarity ?: stringResource(R.string.crownHunt_popupTitle)) +
            (marker.rewardPoints?.let { " · " + stringResource(R.string.crownHunt_spawnReward, it) } ?: "")
        DensityEntry(MapDensityItem("crown:" + marker.id, marker.latitude, marker.longitude,
            selected = selectedCrown == marker.id), marker.id, true, marker.iconRes,
            marker.discColorArgb, marker.glyphColorArgb, label)
    }
    val items = entries.map { it.item }
    val groups = remember(items, bucket) { MapDensityPolicy.groups(items, bucket) }
    val byId = entries.associateBy { it.item.id }
    val compactGroups = groups.filter { it.count > 1 }
    val masked = compactGroups.flatMap { it.members }.mapNotNull { byId[it.id] }
    val hiddenIncidents = masked.filterNot { it.crown }.map { it.sourceId }.toSet()
    val hiddenCrowns = masked.filter { it.crown }.map { it.sourceId }.toSet()
    LaunchedEffect(surface, incidents, hiddenIncidents, hiddenCrowns) {
        surface.setGroupedMarkerIds(hiddenIncidents, hiddenCrowns)
        surface.setIncidentMarkers(incidents)
    }
    DisposableEffect(surface) {
        onDispose { surface.setGroupedMarkerIds(emptySet(), emptySet()) }
    }
    var selection by remember { mutableStateOf<List<String>?>(null) }
    val currentSelection = selection?.mapNotNull { byId[it] }.orEmpty()
    LaunchedEffect(currentSelection.isEmpty()) { if (currentSelection.isEmpty()) selection = null }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val sidePx = with(LocalDensity.current) { 48.dp.toPx() }
        compactGroups.forEach { group ->
            val entry = byId[group.anchor.id] ?: return@forEach
            val point = surface.screenPositionFor(group.anchor.latitude, group.anchor.longitude)
            if (point != null && point.trustworthy && point.x >= -sidePx && point.y >= -sidePx &&
                point.x <= widthPx + sidePx && point.y <= heightPx + sidePx) {
                val description = stringResource(R.string.mapDensity_groupLabel, group.count) +
                    (if (group.critical) ". " + stringResource(R.string.mapDensity_hazardsIncluded) else "") +
                    (if (group.selected) ". " + stringResource(R.string.mapDensity_selected) else "")
                Box(Modifier.offset { IntOffset((point.x - sidePx / 2).roundToInt(), (point.y - sidePx / 2).roundToInt()) }
                    .size(48.dp).semantics { contentDescription = description }
                    .clickable(role = Role.Button) { selection = group.members.map { it.id } }
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
    if (selection != null && currentSelection.isNotEmpty()) {
        MapDetailSheet(title = stringResource(R.string.mapDensity_title), onDismiss = { selection = null },
            modifier = Modifier.testTag("map_density_details")) {
            Text(stringResource(R.string.mapDensity_choose))
            currentSelection.forEach { entry ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) {
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
