package com.kungsbackacarcommunity.app.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackReportTest {

    private val context = FeedbackClientContext("1.2.3", "Android 14", "Google Pixel 8")

    @Test
    fun `description is required`() {
        assertEquals(
            FeedbackReportError.DESCRIPTION_REQUIRED,
            FeedbackReports.validate(FeedbackReportForm(description = "   ")),
        )
        assertNull(FeedbackReports.validate(FeedbackReportForm(description = "It broke")))
    }

    @Test
    fun `toInput trims fields, drops empty summary, and attaches context`() {
        val input =
            FeedbackReports.toInput(
                FeedbackReportForm(summary = "  ", description = "  Map fails  "),
                context,
            )
        assertNull(input!!.summary)
        assertEquals("Map fails", input.description)
        assertEquals("1.2.3", input.appVersion)
        assertEquals("Android 14", input.osVersion)
        assertEquals("Google Pixel 8", input.deviceModel)
    }

    @Test
    fun `toInput keeps a provided summary and bounds long text`() {
        val input =
            FeedbackReports.toInput(
                FeedbackReportForm(
                    summary = "x".repeat(200),
                    description = "y".repeat(FeedbackReports.MAX_DESCRIPTION_LENGTH + 500),
                ),
                context,
            )
        assertEquals(FeedbackReports.MAX_SUMMARY_LENGTH, input!!.summary!!.length)
        assertEquals(FeedbackReports.MAX_DESCRIPTION_LENGTH, input.description.length)
    }

    @Test
    fun `toInput returns null for an invalid form`() {
        assertNull(FeedbackReports.toInput(FeedbackReportForm(description = ""), context))
    }

    @Test
    fun `blank context fields become null`() {
        val input =
            FeedbackReports.toInput(
                FeedbackReportForm(description = "It broke"),
                FeedbackClientContext(appVersion = "", osVersion = null, deviceModel = "  "),
            )
        assertNull(input!!.appVersion)
        assertNull(input.osVersion)
        assertNull(input.deviceModel)
        assertTrue(input.description.isNotEmpty())
    }
}
