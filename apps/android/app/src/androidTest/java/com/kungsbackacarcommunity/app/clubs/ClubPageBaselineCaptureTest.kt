package com.kungsbackacarcommunity.app.clubs

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.LocalAeroBackAvailable
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/**
 * Capture-only host: the current production owner page with local fixture data.
 * Parent owns all Gradle/device execution. No Firebase account, network image,
 * callable mutation, or production UI seam is introduced by this test.
 *
 * One invocation saves five viewport captures for EACH of light/dark, under
 * Pictures/carcommunity-club-page/<clubPageCaptureRun> (API 29+). AGP cleanup
 * cannot remove these test-owned MediaStore images. Pull and inspect every PNG
 * for the baseline/after comparison; captureRun identifies the source revision.
 */
@RunWith(AndroidJUnit4::class)
class ClubPageBaselineCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureCurrentOwnerClubLightAndDark() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("clubPageCaptureRun") ?: "baseline"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val language = args.getString("clubPageLocale") ?: "sv"
        require(language in setOf("sv", "en"))
        val scale = args.getString("clubPageFontScale")?.toFloat() ?: 1f
        require(scale.isFinite() && scale in 1f..2f)
        val locale = Locale.forLanguageTag(language)
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(locale) }
        val localized = ContextThemeWrapper(compose.activity, compose.activity.theme).apply {
            applyOverrideConfiguration(config)
        }
        val previousLocale = Locale.getDefault()
        val previousZone = TimeZone.getDefault()
        Locale.setDefault(locale)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Stockholm"))
        try {
            val target = instrumentation.targetContext
            val repository = ClubPageBaselineRepository(target.packageName)
            val dark = mutableStateOf(false)
            compose.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(
                    LocalContext provides localized,
                    LocalConfiguration provides config,
                    LocalDensity provides Density(density, scale),
                    LocalAeroBackAvailable provides true,
                ) {
                    KccTheme(darkTheme = dark.value) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxSize()) {
                                Text(
                                    "LOKALA TESTDATA · KLUBBSIDA",
                                    Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Box(Modifier.weight(1f)) {
                                    // A fresh production composition resets its own scroll and loading state.
                                    key(dark.value) {
                                        SharedClubRoute(repository.ownerUid, repository = repository)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (isDark in listOf(false, true)) {
                compose.runOnIdle { dark.value = isDark }
                compose.onNodeWithText(repository.club.name).assertIsDisplayed()
                compose.waitUntil(timeoutMillis = 10_000) {
                    repository.membersRequests.get() > 0 && repository.eventsRequests.get() > 0
                }
                val variant = "${if (isDark) "dark" else "light"}-$language-${scale.toString().replace('.', '_')}"
                capture(run, "01-identity-$variant")
                alignSection(localized.getString(R.string.clubs_statistics))
                capture(run, "02-statistics-$variant")
                alignSection(localized.getString(R.string.clubs_calendar))
                capture(run, "03-calendar-$variant")
                alignSection(localized.getString(R.string.clubs_members))
                capture(run, "04-members-upper-$variant")
                alignSection(repository.roster.last().displayName)
                capture(run, "05-members-lower-$variant")
            }
            compose.runOnIdle { assertEquals("Capture must not execute management actions", 0, repository.mutations.get()) }
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousZone)
        }
    }

    /** Scroll the existing page, without adding tags or layout hooks to production. */
    private fun alignSection(label: String) {
        val target = compose.onNodeWithText(label)
        target.performScrollTo()
        compose.waitForIdle()
        // The page scroll must contain this section and have no scrolling ancestor.
        // Nested lists/pickers must never win by semantics traversal order.
        val scroll = compose.onNode(
            hasScrollAction() and hasAnyDescendant(hasText(label)) and
                !hasAnyAncestor(hasScrollAction()),
        )
        val delta = target.fetchSemanticsNode().boundsInRoot.top - scroll.fetchSemanticsNode().boundsInRoot.top
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, delta) }
        compose.waitForIdle()
        target.assertIsDisplayed()
    }

    private fun capture(run: String, name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Compare the Compose export with Android's actual composited window.
        // The native mode is diagnostic evidence, not a replacement production UI.
        val nativeWindow = InstrumentationRegistry.getArguments()
            .getString("clubPageCaptureMode") == "window"
        if (nativeWindow) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250) // allow the real Android compositor to present settled frames
            val pausedStates = InstrumentationRegistry.getArguments()
                .getString("clubPagePauseAt")?.split(',').orEmpty()
            if (name in pausedStates) {
                File(instrumentation.targetContext.getExternalFilesDir(null), "club-page-paused.txt")
                    .writeText(name)
                SystemClock.sleep(8_000) // test thread only: parent captures the actual screen via adb
            }
        }
        val bitmap = if (nativeWindow) {
            checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        } else compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            instrumentation.waitForIdleSync()
            check(instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId("android:id/aerr_close")?.isNotEmpty() != true) {
                "An Android ANR overlay invalidates the baseline. Recover before capture."
            }
            val context = instrumentation.targetContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/carcommunity-club-page/$run")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                try {
                    checkNotNull(resolver.openOutputStream(uri)).use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (error: Exception) {
                    resolver.delete(uri, null, null)
                    throw error
                }
            } else {
                val folder = File(context.getExternalFilesDir(null), "club-page/$run")
                check(folder.mkdirs() || folder.isDirectory)
                File(folder, "$name.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        } finally { bitmap.recycle() }
    }
}

/** Existing test repository supplies fail-local unused methods; every page read is overridden. */
private class ClubPageBaselineRepository(private val packageName: String) : ClubChatDesignRepository() {
    val ownerUid = "local-club-owner"
    private val clubId = "local-owner-club"
    private fun drawable(resource: Int) = "android.resource://$packageName/$resource"
    private val membership = ClubMembership(clubId, ClubRole.Owner, "2026-10-01T09:00:00Z")
    val club = Club(
        id = clubId,
        name = "Västkustens motorvänner",
        description = "Vi samlar bilintresserade för lugna gemensamma rundor och träffar längs västkusten. Alla bilar och erfarenheter är välkomna.",
        location = "Kungsbacka, Hallands län",
        visibility = "closed",
        ownerUid = ownerUid,
        logoUrl = drawable(R.drawable.kcc_logo_dark_bg),
        memberCount = 4,
        moderatorCount = 1,
        stats = ClubStats(driveCount = 184, distanceMeters = 12_684_500.0, durationSeconds = 729_600),
    )
    // Local drawable avatars are supplied even though the current row ignores avatarUrl.
    // Do not retrofit avatar rendering into the baseline just to make it look better.
    val roster = listOf(
        ClubMember(ownerUid, "Sofia Karlsson", ClubRole.Owner, drawable(R.drawable.ic_generic_car)),
        ClubMember("local-moderator", "Alva Lindström", ClubRole.Moderator, drawable(R.drawable.ic_crown_band)),
        ClubMember("local-member-a", "Robin Andersson", ClubRole.Member, drawable(R.drawable.ic_generic_car)),
        ClubMember("local-member-b", "Samira Berg", ClubRole.Member, drawable(R.drawable.ic_launcher_foreground)),
    )
    private val zone = ZoneId.of("Europe/Stockholm")
    private val today = LocalDate.now(zone)
    private val calendarEvents = listOf(
        ClubEvent("local-harbour-meet", "Kvällsträff vid hamnen", today.atTime(18, 30).atZone(zone).toInstant().toEpochMilli(),
            today.atTime(20, 0).atZone(zone).toInstant().toEpochMilli(), "Inre hamnen, Kungsbacka"),
        ClubEvent("local-coast-drive", "Gemensam kusttur", today.withDayOfMonth(today.lengthOfMonth()).atTime(11, 0).atZone(zone).toInstant().toEpochMilli(),
            null, "Samling vid stationen"),
    )
    val membersRequests = AtomicInteger()
    val eventsRequests = AtomicInteger()
    val mutations = AtomicInteger()
    override fun observeEnabled() = flowOf(true)
    override fun observePublicAttendanceReady() = flowOf(true)
    override fun observeAccess() = flowOf<ClubAccess>(ClubAccess.Member(membership))
    override fun observeInvitationsRevision() = flowOf<Long?>(0L)
    override suspend fun state() = ClubState(club = club, membership = membership)
    override suspend fun get(clubId: String) = club
    override suspend fun members(clubId: String, cursor: String?): ClubPage<ClubMember> {
        require(clubId == this.clubId && cursor == null)
        membersRequests.incrementAndGet()
        return ClubPage(roster, null)
    }
    override suspend fun events(clubId: String, cursor: String?): ClubPage<ClubEvent> {
        require(clubId == this.clubId && cursor == null)
        eventsRequests.incrementAndGet()
        return ClubPage(calendarEvents, null)
    }
    override suspend fun manage(action: String, fields: Map<String, Any?>, operationId: String): Map<String, Any?> {
        mutations.incrementAndGet()
        error("Baseline capture cannot mutate a club")
    }
    override suspend fun setEventAttendance(clubId: String, eventId: String, attending: Boolean, operationId: String) {
        mutations.incrementAndGet()
        error("Baseline capture cannot change attendance")
    }
}
