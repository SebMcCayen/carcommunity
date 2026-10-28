package com.kungsbackacarcommunity.app.live

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.database.InternalHelpers
import com.google.firebase.database.core.Path
import com.google.firebase.database.snapshot.IndexedNode
import com.google.firebase.database.snapshot.NodeUtilities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real RTDB snapshot parsing; no listener, network read or write. */
@RunWith(AndroidJUnit4::class)
class LiveSessionConvoyParsingTest {
    // Immutable DataSnapshot traversal only needs a reference path. A live
    // FirebaseApp/Repo starts asynchronous SDK work that can race app.delete()
    // between tests. No Repo means this fixture cannot open a connection; the
    // production parser and Firebase's Node -> value conversion stay real.
    private val reference = InternalHelpers.createReference(null, Path("session-fixture"))

    private fun parse(extra: Map<String, Any?>): LiveSessionInfo? {
        val fields = mapOf("id" to "session", "status" to "active", "duration" to "6h") + extra
        return InternalHelpers.createDataSnapshot(
            reference, IndexedNode.from(NodeUtilities.NodeFromJSON(fields)),
        ).toSessionInfo()
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
