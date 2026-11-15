package com.kungsbackacarcommunity.app.roadcameras

import org.junit.Assert.*
import org.junit.Test

class RoadCameraLocationTest {
    private val southEast = RoadCamera("b", RoadCameraType.RoadCondition, "Kungsbacka Syd", 57.45, 12.09, 135.0)
    private val northWest = southEast.copy(id = "a", direction = 315.0, latitude = 57.4501)

    @Test fun oppositeSpeedCamerasShareARealAnchorAndKeepTheirDirectionsAtHighZoom() {
        val first = southEast.copy(id = "first", type = RoadCameraType.Speed)
        val opposite = first.copy(id = "opposite", latitude = first.latitude + 0.0008, direction = 315.0)
        val sites = groupRoadCameraLocations(listOf(opposite, first))
        assertEquals(1, sites.size)
        val group = groupRoadCameraMarkers(sites, 20.0).single()
        assertTrue(group.opposingDirections)
        assertEquals(listOf(first, opposite), group.views)
        assertTrue(group.views.contains(group.camera))
        assertEquals(sites, groupRoadCameraLocations(listOf(first, opposite)))
    }

    @Test fun proximityAloneNeverMakesUnverifiedSpeedCamerasAnOppositeDirectionSite() {
        val first = southEast.copy(id = "a", type = RoadCameraType.Speed)
        for (other in listOf(first.copy(id = "b"), first.copy(id = "b", direction = null),
            first.copy(id = "b", direction = 315.0, name = "Other road"),
            first.copy(id = "b", direction = 315.0, latitude = first.latitude + 0.002))) {
            assertEquals(2, groupRoadCameraLocations(listOf(first, other)).size)
        }
    }

    @Test fun zoomGroupsPreserveAllCamerasAndNeverMixLayerTypes() {
        val cameras = RoadCameraType.entries.flatMap { type -> (0..3).map { i ->
            southEast.copy(id = "${type.wire}-$i", type = type, name = "Site $i", latitude = 57.45 + i * 0.0001)
        } }
        val sites = groupRoadCameraLocations(cameras)
        val groups = groupRoadCameraMarkers(sites, 13.0)
        assertEquals(3, groups.size)
        assertEquals(listOf(4, 4, 4), groups.map { it.views.size })
        assertEquals(cameras.map { it.id }.sorted(), groups.flatMap { it.views }.map { it.id }.sorted())
        assertTrue(groups.all { group -> group.views.map { it.type }.distinct().size == 1 })
        assertEquals(groups, groupRoadCameraMarkers(sites.reversed(), 13.9))
        assertEquals(12, groupRoadCameraMarkers(sites, 17.0).size)
    }

    @Test fun zoomGroupsDoNotJoinChainsAndKeepCountsAcrossAdjacentGridCells() {
        val sites = (0..2).map { i -> RoadCameraLocation(listOf(
            southEast.copy(id = "$i", name = "Site $i", latitude = 57.45 + i * 0.002))) }
        val groups = groupRoadCameraMarkers(sites, 13.0)
        assertEquals(listOf(2, 1), groups.map { it.views.size })
        assertEquals(sites.first().camera, groups.first().camera)
        assertFalse(groups.first().opposingDirections)
        assertTrue(groupRoadCameraMarkers(emptyList(), 13.0).isEmpty())
        assertEquals(groups, groupRoadCameraMarkers(sites, 13.99))
    }

    @Test fun overlappingDirectionsHaveOneStableMarkerAndClockwiseViews() {
        val expected = groupRoadCameraLocations(listOf(southEast, northWest))
        assertEquals(expected, groupRoadCameraLocations(listOf(northWest, southEast)))
        assertEquals(1, expected.size)
        assertEquals(listOf("b", "a"), expected.single().views.map { it.id })
        assertEquals(southEast, expected.single().camera)
    }

    @Test fun onlyNearbyPhotoCamerasWithTheSameNameAndTypeShareAMarker() {
        val cameras = listOf(southEast, northWest.copy(name = " kungsbacka syd "),
            southEast.copy(id = "distant", latitude = 57.46),
            southEast.copy(id = "other-name", name = "Kungsbacka Nord"),
            southEast.copy(id = "traffic", type = RoadCameraType.Traffic),
            southEast.copy(id = "speed1", type = RoadCameraType.Speed),
            southEast.copy(id = "speed2", type = RoadCameraType.Speed))
        assertEquals(6, groupRoadCameraLocations(cameras).size)
    }

    @Test fun proximityChainsCannotJoinSeparateSites() {
        val anchor = southEast.copy(id = "a")
        val middle = anchor.copy(id = "b", latitude = anchor.latitude + 0.0003)
        val far = anchor.copy(id = "c", latitude = anchor.latitude + 0.0006)
        assertEquals(listOf(2, 1), groupRoadCameraLocations(listOf(far, middle, anchor)).map { it.views.size })
    }

    @Test fun bearingsWrapAtNorthAndUnknownViewsSortLast() {
        assertEquals(0, southEast.copy(direction = 360.0).compassIndex())
        assertEquals(0, southEast.copy(direction = 359.0).compassIndex())
        assertEquals(1, southEast.copy(direction = 22.5).compassIndex())
        assertEquals(3, southEast.compassIndex())
        assertEquals(7, northWest.compassIndex())
        for (bearing in listOf(null, -1.0, 361.0, Double.NaN)) {
            assertNull(southEast.copy(direction = bearing).compassIndex())
        }
        val unknown = southEast.copy(id = "unknown", direction = null)
        val north = southEast.copy(id = "north", direction = 360.0)
        assertEquals(listOf("north", "b", "unknown"),
            groupRoadCameraLocations(listOf(unknown, southEast, north)).single().views.map { it.id })
    }
}
