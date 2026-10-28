package com.kungsbackacarcommunity.app.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapLinkNavigatorTest {
    @Before
    fun setUp() {
        MapLinkNavigator.clear()
    }

    @After
    fun tearDown() {
        MapLinkNavigator.clear()
    }

    @Test
    fun `query waits until the consumer is ready after authentication`() = runTest {
        val target = GeoUriTarget.Query("Åsa torg & café")
        val authenticated = CompletableDeferred<Unit>()
        var handled: GeoUriTarget? = null

        MapLinkNavigator.publish(target)
        launch {
            authenticated.await()
            handled = MapLinkNavigator.consume()
        }
        runCurrent()

        assertEquals(target, MapLinkNavigator.pending.value)
        assertNull(handled)
        advanceUntilIdle()
        assertEquals(target, MapLinkNavigator.pending.value)

        authenticated.complete(Unit)
        advanceUntilIdle()

        assertEquals(target, handled)
        assertNull(MapLinkNavigator.pending.value)
        assertNull(MapLinkNavigator.consume())
    }

    @Test
    fun `newest target replaces an unconsumed query or coordinate`() {
        val stale = GeoUriTarget.Query("Kungsbacka torg")
        val point =
            GeoUriTarget.Point(
                LatLng(longitude = 12.08, latitude = 57.48),
                label = "Torget",
            )
        val latest = GeoUriTarget.Query("Åsa torg & café")

        MapLinkNavigator.publish(stale)
        MapLinkNavigator.publish(point)
        assertEquals(point, MapLinkNavigator.pending.value)
        MapLinkNavigator.publish(latest)

        assertEquals(latest, MapLinkNavigator.pending.value)
        assertEquals(latest, MapLinkNavigator.consume())
        assertNull(MapLinkNavigator.consume())
    }

    @Test
    fun `coordinate and its label are consumed once`() {
        val target =
            GeoUriTarget.Point(
                LatLng(longitude = 12.08, latitude = 57.48),
                label = "Åsa torg & café",
            )

        MapLinkNavigator.publish(target)

        assertEquals(target, MapLinkNavigator.consume())
        assertNull(MapLinkNavigator.pending.value)
        assertNull(MapLinkNavigator.consume())
    }

    @Test
    fun `clear discards the pending query and permits a later link`() {
        MapLinkNavigator.publish(GeoUriTarget.Query("Stale destination"))

        MapLinkNavigator.clear()

        assertNull(MapLinkNavigator.pending.value)
        assertNull(MapLinkNavigator.consume())
        val next = GeoUriTarget.Query("Åsa torg & café")
        MapLinkNavigator.publish(next)
        assertEquals(next, MapLinkNavigator.consume())
    }

    @Test
    fun `an identical link can be handled again after consumption`() {
        MapLinkNavigator.publish(GeoUriTarget.Query("Åsa torg & café"))
        assertEquals(GeoUriTarget.Query("Åsa torg & café"), MapLinkNavigator.consume())
        assertNull(MapLinkNavigator.pending.value)

        MapLinkNavigator.publish(GeoUriTarget.Query("Åsa torg & café"))

        assertEquals(GeoUriTarget.Query("Åsa torg & café"), MapLinkNavigator.pending.value)
        assertEquals(GeoUriTarget.Query("Åsa torg & café"), MapLinkNavigator.consume())
        assertNull(MapLinkNavigator.pending.value)
    }

    @Test
    fun `consuming or clearing without a link leaves no pending target`() {
        assertNull(MapLinkNavigator.consume())

        MapLinkNavigator.clear()

        assertNull(MapLinkNavigator.pending.value)
        assertNull(MapLinkNavigator.consume())
    }
}
