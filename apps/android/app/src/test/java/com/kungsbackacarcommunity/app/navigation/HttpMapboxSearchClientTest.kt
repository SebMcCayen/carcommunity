package com.kungsbackacarcommunity.app.navigation

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HttpMapboxSearchClientTest {
    @Test
    fun `Swedish query reaches the injected transport as one escaped parameter`() = runTest {
        val requests = mutableListOf<String>()
        val client =
            HttpMapboxSearchClient(
                token = "pk.fixture",
                language = "sv",
                responseReader = { url ->
                    requests += url
                    """{"type":"FeatureCollection","features":[]}"""
                },
            )

        val results = client.geocode("Åsa torg & café", proximity = null)

        assertTrue(results.isEmpty())
        val queryParameter = URI(requests.single()).rawQuery.split('&').single { it.startsWith("q=") }
        assertEquals("q=%C3%85sa+torg+%26+caf%C3%A9", queryParameter)
        assertEquals("Åsa torg & café", URLDecoder.decode(queryParameter.removePrefix("q="), "UTF-8"))
    }

    @Test
    fun `blank token returns no matches without calling transport`() = runTest {
        var calls = 0
        val client =
            HttpMapboxSearchClient(
                token = " \t ",
                responseReader = {
                    calls++
                    throw IOException("Transport must not be called without a token")
                },
            )

        assertTrue(client.geocode("Åsa torg & café", proximity = null).isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `null HTTP response fails instead of reporting no matches`() = runTest {
        val client = HttpMapboxSearchClient(token = "pk.fixture", responseReader = { null })

        val failure = runCatching { client.geocode("Åsa torg & café", proximity = null) }.exceptionOrNull()

        assertTrue(
            "Expected IOException for an unsuccessful HTTP response, got $failure",
            failure is IOException,
        )
    }

    @Test
    fun `transport IOException reaches the caller unchanged`() = runTest {
        val offline = IOException("Fixture connection unavailable")
        val client = HttpMapboxSearchClient(token = "pk.fixture", responseReader = { throw offline })

        val failure = runCatching { client.geocode("Åsa torg & café", proximity = null) }.exceptionOrNull()

        assertSame(offline, failure)
    }

    @Test
    fun `valid empty feature collection means no matches`() = runTest {
        val client =
            HttpMapboxSearchClient(
                token = "pk.fixture",
                responseReader = { """{"type":"FeatureCollection","features":[]}""" },
            )

        assertTrue(client.geocode("Nonexistent fixture place", proximity = null).isEmpty())
    }

    @Test
    fun `malformed JSON fails instead of reporting no matches`() = runTest {
        val client = HttpMapboxSearchClient(token = "pk.fixture", responseReader = { "not JSON" })

        val failure = runCatching { client.geocode("Åsa torg & café", proximity = null) }.exceptionOrNull()

        assertTrue("Expected a response parsing failure, got $failure", failure is Exception)
    }

    @Test
    fun `missing features fails instead of reporting no matches`() = runTest {
        val client =
            HttpMapboxSearchClient(
                token = "pk.fixture",
                responseReader = { """{"type":"FeatureCollection"}""" },
            )

        val failure = runCatching { client.geocode("Åsa torg & café", proximity = null) }.exceptionOrNull()

        assertTrue("Expected a missing-features failure, got $failure", failure is Exception)
    }

    @Test
    fun `HTTP transport and payload failures surface as controller search errors`() = runTest {
        val responses: List<suspend (String) -> String?> =
            listOf(
                { null },
                { throw IOException("Fixture connection unavailable") },
                { "not JSON" },
                { """{"type":"FeatureCollection"}""" },
            )

        for ((index, response) in responses.withIndex()) {
            val controller =
                NavigationController(
                    client = HttpMapboxSearchClient(token = "pk.fixture", responseReader = response),
                    originProvider = { null },
                    scope = this,
                )

            controller.onQueryChange("Åsa torg & café")
            advanceUntilIdle()

            assertEquals("Failure fixture $index", NavError.Search, controller.state.value.error)
            assertEquals("Åsa torg & café", controller.state.value.query)
            assertFalse(controller.state.value.searching)
            assertTrue(controller.state.value.suggestions.isEmpty())
        }
    }

    @Test
    fun `valid no matches stops searching without a controller error`() = runTest {
        val controller =
            NavigationController(
                client =
                    HttpMapboxSearchClient(
                        token = "pk.fixture",
                        responseReader = { """{"type":"FeatureCollection","features":[]}""" },
                    ),
                originProvider = { null },
                scope = this,
            )

        controller.onQueryChange("Nonexistent fixture place")
        advanceUntilIdle()

        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.searching)
        assertTrue(controller.state.value.suggestions.isEmpty())
    }
}
