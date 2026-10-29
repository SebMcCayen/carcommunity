package com.kungsbackacarcommunity.app.access

import org.junit.Assert.*
import org.junit.Test

class CarCommunityStatusTest {
    @Test fun productRestrictionPreservesSharedIdentityAndRejectsOtherUid() {
        val data = mapOf("userId" to "own", "suspended" to true, "blacklisted" to false, "version" to 3)
        val status = CarCommunityStatus.fromResponse(data, "own")!!
        assertTrue(status.restricted)
        assertFalse(status.sharedRestricted)
        assertFalse(status.blacklisted)
        assertEquals(3L, status.version)
        assertNull(CarCommunityStatus.fromResponse(data, "other"))
        assertNull(CarCommunityStatus.fromProjection("own", emptyMap(), emptyMap()))
        val projected = CarCommunityStatus.fromProjection("own", mapOf("initialized" to true, "inheritedBlocked" to true, "version" to 4L, "blacklistReason" to "Reason"), mapOf("suspended" to false))!!
        assertTrue(projected.blacklisted)
        assertEquals("Reason", projected.reason)
        assertFalse(projected.sharedRestricted)
    }
}
