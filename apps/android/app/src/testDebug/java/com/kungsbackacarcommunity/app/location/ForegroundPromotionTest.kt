package com.kungsbackacarcommunity.app.location

import android.app.ForegroundServiceStartNotAllowedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForegroundPromotionTest {
    @Test fun acceptedPromotionContinuesWithoutDeferring() {
        var deferred = false
        assertTrue(ForegroundPromotion.attempt(onRefused = { deferred = true }, promote = {}))
        assertFalse(deferred)
    }

    @Test fun exactPlatformRefusalStopsAndDefersBeforeWorkCanContinue() {
        val events = mutableListOf<String>()
        val promoted = ForegroundPromotion.attempt(
            onRefused = { events += "stop"; events += "defer" },
            promote = { throw ForegroundServiceStartNotAllowedException("test refusal") },
        )
        if (promoted) events += "GPS"
        assertFalse(promoted)
        assertEquals(listOf("stop", "defer"), events)
    }

    @Test fun unrelatedStateAndPermissionFailuresAreNotHidden() {
        for (failure in listOf(IllegalStateException("unexpected"), SecurityException("permission"))) {
            var deferred = false
            val thrown = assertThrows(failure.javaClass) {
                ForegroundPromotion.attempt(onRefused = { deferred = true }, promote = { throw failure })
            }
            assertEquals(failure, thrown)
            assertFalse(deferred)
        }
    }
}
