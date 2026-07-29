package com.kungsbackacarcommunity.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure Play-listing URI builders (the Intent glue is verified on device). */
class PlayStoreLinkTest {

    private val applicationId = "com.kungsbackacarcommunity.app"

    @Test
    fun `market uri points at this app's listing`() {
        assertEquals(
            "market://details?id=com.kungsbackacarcommunity.app",
            PlayStoreLink.marketUri(applicationId),
        )
    }

    @Test
    fun `web uri is the https play listing fallback`() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.kungsbackacarcommunity.app",
            PlayStoreLink.webUri(applicationId),
        )
    }

    @Test
    fun `both uris carry the same application id`() {
        assertTrue(PlayStoreLink.marketUri(applicationId).endsWith(applicationId))
        assertTrue(PlayStoreLink.webUri(applicationId).endsWith(applicationId))
    }
}
