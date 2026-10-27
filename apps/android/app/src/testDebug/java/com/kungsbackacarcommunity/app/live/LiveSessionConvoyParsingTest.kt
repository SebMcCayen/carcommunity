package com.kungsbackacarcommunity.app.live

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.snapshot.IndexedNode
import com.google.firebase.database.snapshot.NodeUtilities
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real RTDB snapshot parsing; no listener, network read or write. */
@RunWith(AndroidJUnit4::class)
class LiveSessionConvoyParsingTest {
    private val app = FirebaseApp.initializeApp(
        ApplicationProvider.getApplicationContext(),
        FirebaseOptions.Builder().setApplicationId("1:123:android:test")
            .setApiKey("test-api-key").setProjectId("convoy-parser-test").build(),
        "convoy-parser",
    )
    private val reference = FirebaseDatabase.getInstance(app, "https://convoy-parser-test.firebaseio.com").reference
    @After fun cleanUp() = app.delete()

    private fun parse(extra: Map<String, Any?>): LiveSessionInfo? {
        val fields = mapOf("id" to "session", "status" to "active", "duration" to "6h") + extra
        return DataSnapshot(reference, IndexedNode.from(NodeUtilities.NodeFromJSON(fields))).toSessionInfo()
    }

    @Test fun retainsBackendStampedOwningConvoy() {
        val session = parse(mapOf("convoyId" to "owning-convoy", "convoyAutoStarted" to true))!!
        assertEquals("owning-convoy", session.convoyId)
        assertTrue(session.convoyAutoStarted)
    }
    @Test fun manualAndLegacyNodesHaveNoOwningConvoy() {
        assertNull(parse(emptyMap())!!.convoyId)
        assertNull(parse(mapOf("convoyAutoStarted" to true))!!.convoyId)
        assertNull(parse(mapOf("convoyId" to " "))!!.convoyId)
    }
}
