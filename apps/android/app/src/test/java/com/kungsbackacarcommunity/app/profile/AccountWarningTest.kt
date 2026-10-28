package com.kungsbackacarcommunity.app.profile

import org.junit.Assert.*
import org.junit.Test

class AccountWarningTest {
    @Test fun `network delay counts against server validity and expiry is exclusive`() {
        val warning = AccountWarning.decode(mapOf("id" to "w1", "reason" to "Respect others", "remainingMillis" to 1000), 5000)!!
        assertTrue(warning.active(5999))
        assertFalse(warning.active(6000))
        assertFalse(warning.active(7000))
    }
    @Test fun `indefinite warning stays active and malformed reason is rejected`() {
        val warning = AccountWarning.decode(mapOf("id" to "w1", "reason" to "Respect others"), 5000)!!
        assertTrue(warning.active(Long.MAX_VALUE))
        assertNull(AccountWarning.decode(mapOf("id" to "w1", "internalNote" to "private"), 0))
    }
}
