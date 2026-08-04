package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.live.LiveCoordinate
import com.kungsbackacarcommunity.app.live.LiveLocationRepository
import com.kungsbackacarcommunity.app.live.LiveMarker
import com.kungsbackacarcommunity.app.live.LiveSessionDuration
import com.kungsbackacarcommunity.app.live.LiveSessionInfo
import com.kungsbackacarcommunity.app.live.NearbyLiveSession
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the per-uid marker-feed combining that MapRoute performs (own
 * observeLatest + combined per-uid others), using a fake repository. This is
 * the pure flow logic behind the composable, kept JVM-testable.
 */
class MapMarkerFeedTest {

    /** Fake backed by one MutableStateFlow per uid, so tests can push updates. */
    private class FakeRepo : LiveLocationRepository {
        val latest = mutableMapOf<String, MutableStateFlow<LiveMarker?>>()

        fun flowFor(uid: String): MutableStateFlow<LiveMarker?> =
            latest.getOrPut(uid) { MutableStateFlow(null) }

        override suspend fun startSession(duration: LiveSessionDuration) = Unit

        override suspend fun updatePosition(coordinate: LiveCoordinate) = Unit

        override suspend fun stopSession() = Unit

        override suspend fun hideMeNow() = Unit

        override fun observeOwnSession(uid: String): Flow<LiveSessionInfo?> = flowOf(null)

        override fun observeLatest(uid: String): Flow<LiveMarker?> = flowFor(uid)

        override suspend fun listNearby(center: LatLng, radiusMeters: Double) =
            emptyList<NearbyLiveSession>()
    }

    /** Mirrors MapRoute's combine: own + per-uid others → drawn marker list. */
    private fun feed(
        repo: LiveLocationRepository,
        uid: String,
        others: List<String>,
    ): Flow<List<MapMarker>> {
        val ownFlow = repo.observeLatest(uid)
        val otherKey = others.filter { it.isNotBlank() && it != uid }.distinct()
        val othersFlow: Flow<List<LiveMarker?>> =
            if (otherKey.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(otherKey.map { repo.observeLatest(it) }) { it.toList() }
            }
        return ownFlow.let { own ->
            combine(own, othersFlow) { o, others2 -> MapMarkers.markers(o, others2) }
        }
    }

    private fun marker(uid: String, lng: Double, lat: Double) =
        LiveMarker(uid = uid, latitude = lat, longitude = lng)

    @Test
    fun `combines own and other sharing members`() = runTest {
        val repo = FakeRepo()
        repo.flowFor("me").value = marker("me", 12.0, 57.0)
        repo.flowFor("a").value = marker("a", 13.0, 58.0)
        repo.flowFor("b").value = marker("b", 14.0, 59.0)

        val markers = feed(repo, "me", listOf("a", "b")).first()
        assertEquals(listOf("me", "a", "b"), markers.map { it.uid })
        assertEquals(MapMarkerKind.OWN, markers[0].kind)
    }

    @Test
    fun `members not sharing are absent from the feed`() = runTest {
        val repo = FakeRepo()
        repo.flowFor("me").value = marker("me", 12.0, 57.0)
        // "a" never sets a marker (null = stopped/never shared).
        repo.flowFor("b").value = marker("b", 14.0, 59.0)

        val markers = feed(repo, "me", listOf("a", "b")).first()
        assertEquals(listOf("me", "b"), markers.map { it.uid })
    }

    @Test
    fun `own-only feed when there are no participant uids`() = runTest {
        val repo = FakeRepo()
        repo.flowFor("me").value = marker("me", 12.0, 57.0)

        val markers = feed(repo, "me", emptyList()).first()
        assertEquals(listOf("me"), markers.map { it.uid })
    }

    @Test
    fun `empty feed when nobody in the roster is sharing`() = runTest {
        val repo = FakeRepo()
        // own null, others null
        val markers = feed(repo, "me", listOf("a", "b")).first()
        assertTrue(markers.isEmpty())
    }

    @Test
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun `updates propagate when a member starts sharing`() = runTest {
        val repo = FakeRepo()
        repo.flowFor("me").value = marker("me", 12.0, 57.0)
        // Collect both emissions in ONE collection so the second is genuinely
        // the propagated update (a fresh first() would re-collect from the start
        // and never prove propagation). Launch the collector, then push "a".
        val emissions =
            async {
                feed(repo, "me", listOf("a"))
                    .map { list -> list.map { it.uid } }
                    .take(2)
                    .toList()
            }
        runCurrent()
        repo.flowFor("a").value = marker("a", 13.0, 58.0)
        // Before: only self. After "a" shares: self + a, from the same stream.
        assertEquals(listOf(listOf("me"), listOf("me", "a")), emissions.await())
    }
}
