package com.kungsbackacarcommunity.app.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.kungsbackacarcommunity.app.MainActivity
import com.kungsbackacarcommunity.app.R
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real NotificationManager + production MainActivity lifecycle; run in a config-less APK on an
 * isolated test device. No Firebase initialization, service start, location request or network
 * fixture. A configured APK is skipped before any Activity is launched.
 *
 * "Cold" means a fresh Activity, not process death: instrumentation shares the app process.
 * Notification entry sends the actual content PendingIntent, without automating System UI.
 * FLAG_ONGOING_EVENT and FLAG_NO_CLEAR are checked after the system accepts each fixture.
 * FLAG_FOREGROUND_SERVICE is deliberately not forged: Android may strip that bit from an ordinary
 * notify() call. These fixtures cannot certify live GPS/FGS continuity or launcher badge rendering;
 * the helper's FGS-bit branch belongs in the parent's unit tests and real-service device QA.
 */
@RunWith(AndroidJUnit4::class)
class SystemNotificationLifecycleTest {
    // Used only for bounded condition polling; no replacement Activity or Compose content.
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val namespace = "system_notification_lifecycle_${UUID.randomUUID()}"
    private val regularChannel = "${namespace}_regular"
    private val statusChannel = "${namespace}_status"
    private val baseId = (namespace.hashCode() and 0x3fffffff) + 10_000
    private val fixtureKeys = mutableSetOf<NoticeKey>()
    private val fixtureChannels = mutableListOf<String>()
    private val fixtureIntents = mutableListOf<PendingIntent>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var ownedDeepLink: PushDeepLink? = null

    @Before
    fun prepareIsolatedConfiglessFixtures() {
        // Inspect only: do not initialize/delete Firebase apps or alter anyone's signed-in state.
        val googleAppId = context.resources.getIdentifier("google_app_id", "string", context.packageName)
        assumeTrue(
            "Requires a config-less APK: do not run MainActivity against production Firebase",
            googleAppId == 0 && FirebaseApp.getApps(context).isEmpty(),
        )
        assumeTrue(
            "Requires an isolated app install: MainActivity itself clears dismissible notices",
            manager.activeNotifications.isEmpty(),
        )
        assumeTrue("A previous pending push must not be consumed by this test", PushNavigator.pending.value == null)

        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        assertTrue("Notification fixtures require notifications enabled", manager.areNotificationsEnabled())
        createChannel(regularChannel, badging = true)
        createChannel(statusChannel, badging = false)
    }

    @After
    fun removeOnlyOwnedFixtures() {
        try {
            scenario?.close()
        } finally {
            // Never cancelAll(): ids/tags/channels are unique to this test instance.
            fixtureKeys.forEach { manager.cancel(it.tag, it.id) }
            fixtureIntents.forEach(PendingIntent::cancel)
            fixtureChannels.forEach(manager::deleteNotificationChannel)
            ownedDeepLink?.let { link ->
                instrumentation.runOnMainSync {
                    if (PushNavigator.pending.value == link) PushNavigator.consume()
                }
            }
        }
    }

    @Test
    fun coldLauncherEntryClearsUntaggedAndTaggedNoticesButKeepsPersistentStatus() {
        val regular = postRegularNotices()
        val status = postPersistentStatus()
        awaitFixtureKeys(regular + status)

        val activity = launchLauncher()
        activity.onActivity {
            assertEquals(Intent.ACTION_MAIN, it.intent.action)
            assertTrue(it.intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        }
        awaitFixtureKeys(status)
        assertStatusFlags(status)
        assertNull("A launcher entry must not invent a push destination", PushNavigator.pending.value)

        // This post happens strictly AFTER the resume snapshot and must remain a new notice.
        val later = NoticeKey("${namespace}_after_resume", baseId)
        post(later)
        awaitFixtureKeys(status + later)
    }

    @Test
    fun warmStoppedActivityResumesClearsNoticesAndPreservesTheSameStatusNotifications() {
        val activity = launchLauncher()
        val original = AtomicReference<MainActivity>()
        activity.onActivity { original.set(it) }
        activity.moveToState(Lifecycle.State.CREATED) // Real onPause/onStop; no manual onResume call.
        assertEquals(Lifecycle.State.CREATED, activity.state)

        val regular = postRegularNotices()
        val status = postPersistentStatus()
        awaitFixtureKeys(regular + status)
        activity.moveToState(Lifecycle.State.RESUMED)
        activity.onActivity {
            assertSame("This must exercise a warm return, not recreation", original.get(), it)
            assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
        }
        awaitFixtureKeys(status)
        assertStatusFlags(status)
    }

    @Test
    fun notificationPendingIntentResumesExistingActivityAndKeepsItsPushDeepLink() {
        val activity = launchLauncher()
        val original = AtomicReference<MainActivity>()
        activity.onActivity { original.set(it) }
        activity.moveToState(Lifecycle.State.CREATED)

        val link = PushDeepLink(PushTarget.EVENT, "${namespace}_event")
        ownedDeepLink = link
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(KccMessagingService.EXTRA_PUSH_TARGET, link.target.wire)
            .putExtra(KccMessagingService.EXTRA_PUSH_ENTITY_ID, link.entityId)
        val contentIntent = PendingIntent.getActivity(
            context,
            baseId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ).also(fixtureIntents::add)
        val regular = postRegularNotices(contentIntent)
        val status = postPersistentStatus()
        awaitFixtureKeys(regular + status)

        val tapped = manager.activeNotifications.first {
            it.tag == "${namespace}_tag_a" && it.id == baseId
        }
        requireNotNull(tapped.notification.contentIntent).send()
        awaitStable("PendingIntent must resume MainActivity and publish its queued deep link") {
            activity.state == Lifecycle.State.RESUMED && PushNavigator.pending.value == link
        }
        activity.onActivity {
            assertSame("singleTop notification entry must reuse MainActivity", original.get(), it)
            assertEquals(link, KccMessagingService.deepLinkFrom(it.intent))
        }
        awaitFixtureKeys(status)
        assertStatusFlags(status)
        // In a config-less APK there is no authenticated shell to consume the queue. Checking it
        // here proves cancellation did not clear/rewrite the target waiting through authentication.
        assertEquals(link, PushNavigator.pending.value)
    }

    private fun launchLauncher(): ActivityScenario<MainActivity> =
        ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER),
        ).also { scenario = it }

    private fun createChannel(id: String, badging: Boolean) {
        fixtureChannels += id
        manager.createNotificationChannel(
            NotificationChannel(id, "Notification lifecycle test", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(badging) },
        )
    }

    private fun postRegularNotices(contentIntent: PendingIntent? = null): Set<NoticeKey> {
        // Identical integer ids with distinct tags catch cancel(id)-only implementations.
        val keys = setOf(
            NoticeKey(null, baseId),
            NoticeKey("${namespace}_tag_a", baseId),
            NoticeKey("${namespace}_tag_b", baseId),
        )
        keys.forEach { post(it, contentIntent = contentIntent) }
        return keys
    }

    private fun postPersistentStatus(): Set<NoticeKey> {
        val ongoing = NoticeKey("${namespace}_ongoing", baseId + 1)
        val noClear = NoticeKey("${namespace}_no_clear", baseId + 2)
        post(ongoing, flags = Notification.FLAG_ONGOING_EVENT)
        post(noClear, flags = Notification.FLAG_NO_CLEAR)
        val keys = setOf(ongoing, noClear)
        awaitStable("System must accept both persistent status fixtures with their flags") {
            val active = manager.activeNotifications.associateBy { NoticeKey(it.tag, it.id) }
            active[ongoing]?.notification?.flags?.and(Notification.FLAG_ONGOING_EVENT) != 0 &&
                active[ongoing] != null &&
                active[noClear]?.notification?.flags?.and(Notification.FLAG_NO_CLEAR) != 0 &&
                active[noClear] != null
        }
        assertStatusFlags(keys)
        return keys
    }

    private fun post(key: NoticeKey, flags: Int = 0, contentIntent: PendingIntent? = null) {
        fixtureKeys += key
        val notice = Notification.Builder(context, if (flags == 0) regularChannel else statusChannel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Local notification lifecycle fixture")
            .setOnlyAlertOnce(true)
            .setOngoing(flags and Notification.FLAG_ONGOING_EVENT != 0)
            .setAutoCancel(flags == 0)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build().apply { this.flags = this.flags or flags }
        manager.notify(key.tag, key.id, notice)
    }

    private fun assertStatusFlags(keys: Set<NoticeKey>) {
        val active = manager.activeNotifications.associateBy { NoticeKey(it.tag, it.id) }
        keys.forEach { key ->
            val notification = requireNotNull(active[key]) { "Persistent fixture disappeared: $key" }.notification
            val expected = if (key.id == baseId + 1) Notification.FLAG_ONGOING_EVENT else Notification.FLAG_NO_CLEAR
            assertEquals("System fixture must retain its actual protection flag", expected, notification.flags and expected)
            assertEquals(statusChannel, notification.channelId)
        }
    }

    private fun awaitFixtureKeys(expected: Set<NoticeKey>) {
        awaitStable("Expected owned notification keys $expected") {
            manager.activeNotifications.map { NoticeKey(it.tag, it.id) }
                .filter { it in fixtureKeys }.toSet() == expected
        }
    }

    private fun awaitStable(description: String, condition: () -> Boolean) {
        var consecutiveMatches = 0
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                consecutiveMatches = if (condition()) consecutiveMatches + 1 else 0
                consecutiveMatches >= 2
            }
        } catch (failure: AssertionError) {
            throw AssertionError(description, failure)
        }
    }

    private data class NoticeKey(val tag: String?, val id: Int)
}
