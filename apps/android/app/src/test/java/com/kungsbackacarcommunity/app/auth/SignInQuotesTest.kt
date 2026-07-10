package com.kungsbackacarcommunity.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the sign-in car-quote pool that replaced the old privacy note:
 * exactly 20 quotes, no duplicate resource ids (a duplicate would silently
 * skew the random pick and hint at a copy-paste slip in the list).
 */
class SignInQuotesTest {

    @Test
    fun quotePool_hasTwentyDistinctEntries() {
        assertEquals(20, signInCarQuoteResIds.size)
        assertEquals(20, signInCarQuoteResIds.distinct().size)
    }
}
