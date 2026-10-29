package com.kungsbackacarcommunity.app.clubs

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production control/dialog, local fakes only. Nothing resolves or fetches the synthetic URL. */
@RunWith(AndroidJUnit4::class)
class ClubCalendarSubscriptionCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private data class Variant(val dark: Boolean, val scale: Float) {
        val suffix get() = "${if (dark) "dark" else "light"}-$scale"
    }
    private val variants = listOf(Variant(false, 1f), Variant(true, 1f), Variant(false, 2f), Variant(true, 2f))
    private fun text(id: Int) = compose.activity.getString(id)
    private fun tag(name: String) = compose.onNodeWithTag("club_calendar_$name")

    private fun noTokenInUi() {
        compose.onAllNodes(hasText(calendarFixtureToken, substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription(calendarFixtureToken, substring = true), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun capture(stage: String, variant: Variant) {
        compose.waitForIdle()
        noTokenInUi()
        val captureNode = if (stage == "baseline") {
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onRoot().assertIsDisplayed()
                .assert(hasAnyDescendant(hasTestTag("club_calendar_subscribe")))
        } else {
            // Window/root enumeration order does not identify the modal. Select
            // its dialog semantics explicitly so a dimmed Activity cannot pass.
            compose.onAllNodes(isDialog()).assertCountEquals(1)
            val dialog = compose.onNode(isDialog()).assertIsDisplayed()
                .assert(hasAnyDescendant(hasText(text(R.string.clubCalendar_subscribe))))
                .assert(hasAnyDescendant(hasText(text(R.string.clubs_close))))
            val expectedStage = when (stage) {
                "instructions" -> hasText(text(R.string.clubCalendar_androidInstructions))
                "revocation" -> hasTestTag("club_calendar_revoke")
                "issued-copy-action" -> hasTestTag("club_calendar_copy")
                "revoked" -> hasText(text(R.string.clubCalendar_inactive))
                else -> error("Unknown calendar capture stage: $stage")
            }
            dialog.assert(hasAnyDescendant(expectedStage))
            compose.onNode(expectedStage and hasAnyAncestor(isDialog())).assertIsDisplayed()
            dialog
        }
        val bitmap = captureNode.captureToImage().asAndroidBitmap()
        val file = File(compose.activity.getExternalFilesDir(null), "calendar-subscription-$stage-${variant.suffix}.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun currentAppControlBaselineBeforeOpeningIsASeparateFixture() {
        val selected = mutableStateOf(variants.first())
        val repository = CalendarFixtureRepository()
        compose.setContent {
            val variant = selected.value
            KccTheme(darkTheme = variant.dark) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, variant.scale)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(16.dp)) {
                            Text("LOKALA TESTDATA · FÖRE DIALOGEN")
                            ClubCalendarSubscriptionControl("local-club", repository)
                        }
                    }
                }
            }
        }
        for (variant in variants) {
            compose.runOnIdle { selected.value = variant }
            tag("subscribe").assertIsDisplayed()
            tag("issue").assertDoesNotExist()
            capture("baseline", variant)
        }
        compose.runOnIdle { assertTrue(repository.requests.isEmpty()) }
    }

    @Test fun instructionsAndRevocationRemainReachableInBothThemesAtLargeText() {
        val selected = mutableStateOf(variants.first())
        val generation = mutableStateOf(0)
        val repository = mutableStateOf(CalendarVisualRepository())
        compose.setContent {
            val variant = selected.value
            KccTheme(darkTheme = variant.dark) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, variant.scale)) {
                    key(generation.value) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.padding(16.dp)) {
                                Text("LOKALA TESTDATA · KLUBBKALENDER")
                                ClubCalendarSubscriptionControl("local-club", repository.value)
                            }
                        }
                    }
                }
            }
        }
        for (variant in variants) {
            compose.runOnIdle {
                selected.value = variant
                generation.value++
                repository.value = CalendarVisualRepository()
            }
            tag("subscribe").performClick()
            compose.onNodeWithText(text(R.string.clubCalendar_androidInstructions)).performScrollTo().assertIsDisplayed()
            capture("instructions", variant)
            compose.onNodeWithText(text(R.string.clubCalendar_replaceWarning)).performScrollTo().assertIsDisplayed()
            tag("revoke").performScrollTo().assertIsEnabled()
            capture("revocation", variant)
            compose.runOnIdle { assertEquals(listOf("status"), repository.value.actions) }
            tag("issue").performScrollTo().performClick()
            tag("copy").performScrollTo().assertIsDisplayed()
            capture("issued-copy-action", variant)
            tag("revoke").performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.clubCalendar_inactive)).performScrollTo().assertIsDisplayed()
            tag("copy").assertDoesNotExist()
            tag("revoke").assertDoesNotExist()
            capture("revoked", variant)
            compose.runOnIdle { assertEquals(listOf("status", "issue", "revoke"), repository.value.actions) }
            compose.onNodeWithText(text(R.string.clubs_close)).performClick()
        }
    }
}

private class CalendarVisualRepository : ClubCalendarSubscriptionRepository {
    val actions = mutableListOf<String>()
    private var version = 3L
    private var active = true
    override suspend fun manage(clubId: String, action: String, expectedVersion: Long?): CalendarSubscription {
        require(clubId == "local-club")
        actions += action
        return when (action) {
            "status" -> { require(expectedVersion == null); CalendarSubscription(active, version) }
            "issue" -> {
                require(expectedVersion == version)
                active = true
                CalendarSubscription(true, ++version, calendarFixtureUrl)
            }
            "revoke" -> {
                require(expectedVersion == version)
                active = false
                CalendarSubscription(false, ++version)
            }
            else -> error("Unsupported fixture action")
        }
    }
}
