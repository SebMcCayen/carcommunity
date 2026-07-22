package com.kungsbackacarcommunity.app.media

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [resolvePickOutcome] — the cancel-vs-failure decision extracted
 * from the Compose photo-pick launcher. This is the seam that fixes the profile
 * avatar "nothing happens": a chosen-but-unreadable pick must resolve to
 * [PickOutcome.Failed] (so the caller can surface an error), NOT be conflated
 * with a user cancel.
 */
class PickOutcomeTest {

    private val image = PickedImage(bytes = ByteArray(10), contentType = "image/jpeg")

    @Test
    fun `null source is a cancel and never reads`() = runTest {
        var read = false
        val outcome =
            resolvePickOutcome<String>(source = null) {
                read = true
                image
            }
        assertEquals(PickOutcome.Cancelled, outcome)
        assertFalse("read must not run for a cancel", read)
    }

    @Test
    fun `a chosen pick that reads is Picked and carries the image`() = runTest {
        val outcome = resolvePickOutcome(source = "content://photo") { image }
        assertTrue(outcome is PickOutcome.Picked)
        assertEquals(image, (outcome as PickOutcome.Picked).image)
    }

    @Test
    fun `a chosen pick whose read returns null is Failed, not a silent cancel`() = runTest {
        val outcome = resolvePickOutcome<String>(source = "content://photo") { null }
        assertEquals(PickOutcome.Failed, outcome)
    }
}
