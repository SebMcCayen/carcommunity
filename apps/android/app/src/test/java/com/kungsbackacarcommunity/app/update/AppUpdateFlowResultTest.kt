package com.kungsbackacarcommunity.app.update

import android.app.Activity
import com.google.android.play.core.install.model.ActivityResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading what Play's update flow hands back — the branch that decides whether
 * the member is bothered at all, and whether they are left with a way forward.
 */
class AppUpdateFlowResultTest {

    @Test
    fun `an accepted flow says nothing`() {
        assertEquals(
            AppUpdateFlowOutcome.ACCEPTED,
            AppUpdateFlowResult.read(Activity.RESULT_OK),
        )
    }

    @Test
    fun `a decline is an answer, not a failure`() {
        // Backing out of Play's consent sheet must not produce a message, and
        // must not re-prompt: the suppression window was recorded when Update
        // was pressed, so the next launch stays quiet.
        assertEquals(
            AppUpdateFlowOutcome.DECLINED,
            AppUpdateFlowResult.read(Activity.RESULT_CANCELED),
        )
    }

    @Test
    fun `Play's own failure code is a failure`() {
        assertEquals(
            AppUpdateFlowOutcome.FAILED,
            AppUpdateFlowResult.read(ActivityResult.RESULT_IN_APP_UPDATE_FAILED),
        )
    }

    @Test
    fun `an unrecognised code errs towards offering a way forward`() {
        // Anything that is neither an acceptance nor a decline is treated as a
        // failure, so a future Play result code offers the store listing rather
        // than silently dropping the update.
        for (code in listOf(2, 7, -99, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(
                "result code $code",
                AppUpdateFlowOutcome.FAILED,
                AppUpdateFlowResult.read(code),
            )
        }
    }
}
