package com.kungsbackacarcommunity.app.clubs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure reply validation: synthetic bearer only, no Firebase initialization or network. */
class ClubCalendarSubscriptionParserTest {
    private val project = "demo-calendar"
    private val token = "A".repeat(43)
    private val host = "europe-west1-$project.cloudfunctions.net"
    private val path = "/clubCalendar-feed/calendar.ics"
    private val url = "https://$host$path?token=$token"
    private fun reply(version: Any? = 7L, active: Any? = true, link: Any? = url) =
        mapOf("active" to active, "version" to version, "url" to link)

    private fun rejected(value: Any?, forProject: String = project) {
        try {
            parseCalendarSubscription(value, forProject)
            fail("Malformed calendar response was accepted")
        } catch (error: ClubException) {
            assertEquals("unavailable", error.code)
        }
    }

    @Test fun validStatusDoesNotInventOrRecoverABearer() {
        for (active in listOf(false, true)) {
            for (version in listOf<Number>(0, 1L, 23.0, 9_007_199_254_740_991L)) {
                val parsed = parseCalendarSubscription(mapOf("active" to active, "version" to version), project)
                assertEquals(active, parsed.active)
                assertEquals(version.toLong(), parsed.version)
                assertNull(parsed.url)
            }
        }
        assertFalse(parseCalendarSubscription(reply(version = 0, active = false, link = null), project).active)
    }

    @Test fun issuedReplyKeepsOnlyTheExactHttpsFeedUrlAndCurrentRevision() {
        val parsed = parseCalendarSubscription(reply(), project)
        assertTrue(parsed.active)
        assertEquals(7L, parsed.version)
        assertEquals(url, parsed.url)
        // Backend metadata is not a new protocol version or a second copy target.
        val extra = reply() + mapOf("webcalUrl" to url.replace("https:", "webcal:"), "limits" to emptyMap<String, Any>())
        assertEquals(parsed, parseCalendarSubscription(extra, project))
    }

    @Test fun credentialsPortsWrongOriginsAndSchemesAreRejected() {
        listOf(
            url.replace("https:", "http:"), url.replace("https:", "webcal:"),
            "file://$path?token=$token", "javascript:alert(1)", "//$host$path?token=$token",
            url.replace(host, "attacker.test"), url.replace(host, "$host.attacker.test"),
            url.replace(host, "attacker.test@$host"), url.replace(host, "user:pass@$host"),
            url.replace(host, "$host:443"), url.replace(host, "$host:8443"),
            url.replace(host, "$host."), url.replace("https://", "https:\\\\"),
        ).forEach { rejected(reply(link = it)) }
    }

    @Test fun duplicateQueriesFragmentsEncodedTokensAndPathTraversalAreRejected() {
        listOf(
            "$url#", "$url#calendar", "$url&token=$token", "$url&other=value",
            url.replace("?token=", "?other=value&token="), url.replace("token=", "Token="),
            url.replace(token, "A".repeat(42)), url.replace(token, "A".repeat(44)),
            url.replace(token, "A".repeat(42) + "%41"), url.replace(token, "A".repeat(42) + "+"),
            url.replace(path, "/clubCalendar-feed/../clubCalendar-feed/calendar.ics"),
            url.replace(path, "/clubCalendar-feed/%2e%2e/clubCalendar-feed/calendar.ics"),
            url.replace(path, "/clubCalendar-feed%2Fcalendar.ics"),
            url.replace(path, "/clubCalendar-feed//calendar.ics"),
            url.replace(path, "$path/"), url.replace(path, "/clubCalendar-feed/calendar.ics;extra"),
            "$url\n", " $url", "https://[invalid",
        ).forEach { rejected(reply(link = it)) }
    }

    @Test fun projectChangesCannotReuseAnIssuedUrl() {
        rejected(reply(), "demo-other-project")
        for (invalid in listOf("", "demo", "Demo-calendar", "demo_calendar", "demo/calendar", "demo-calendar.attacker")) {
            rejected(reply(), invalid)
        }
        val other = "demo-other-project"
        val moved = url.replace(project, other)
        assertEquals(moved, parseCalendarSubscription(reply(link = moved), other).url)
    }

    @Test fun invalidNonfiniteFractionalAndUnsupportedFutureRevisionsAreRejected() {
        for (version in listOf(null, "1", "future-unsupported", true, -1, -0.5, 1.5,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            9_007_199_254_740_992L, Long.MAX_VALUE, Double.MAX_VALUE)) {
            rejected(reply(version = version))
        }
    }

    @Test fun malformedStatusAndInactiveIssuedLinksFailClosed() {
        listOf(null, emptyMap<String, Any>(), "status", emptyList<Any>(),
            reply(active = null), reply(active = "true"), reply(active = 1),
            reply(active = false), reply(link = ""), reply(link = 123),
            reply(link = listOf("link")), reply(link = mapOf("url" to url)),
        ).forEach { rejected(it) }
    }
}
