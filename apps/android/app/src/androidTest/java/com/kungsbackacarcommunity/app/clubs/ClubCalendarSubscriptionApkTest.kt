package com.kungsbackacarcommunity.app.clubs

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Separate packaging checks: these do not initialize Firebase or open the calendar UI. */
@RunWith(AndroidJUnit4::class)
class ClubCalendarSubscriptionApkTest {
    @Suppress("DEPRECATION")
    @Test fun installedTestApkAndTargetApkParseAndTargetTheCurrentApplication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val test = instrumentation.context
        val appArchive = requireNotNull(target.packageManager.getPackageArchiveInfo(
            target.applicationInfo.sourceDir, PackageManager.GET_ACTIVITIES,
        ))
        val testArchive = requireNotNull(test.packageManager.getPackageArchiveInfo(
            test.applicationInfo.sourceDir, PackageManager.GET_INSTRUMENTATION,
        ))
        assertEquals(target.packageName, appArchive.packageName)
        assertEquals(test.packageName, testArchive.packageName)
        assertNotEquals(appArchive.packageName, testArchive.packageName)
        assertTrue(appArchive.activities.orEmpty().isNotEmpty())
        assertTrue(testArchive.instrumentation.orEmpty().any { it.targetPackage == target.packageName })
    }

    @Test fun replyParserAlsoRunsInsideTheTestApkWithLocalInputs() {
        val status = parseCalendarSubscription(mapOf("active" to false, "version" to 0), "demo-calendar")
        assertFalse(status.active)
        assertEquals(0L, status.version)
        assertNull(status.url)
        val issued = parseCalendarSubscription(
            mapOf("active" to true, "version" to 1, "url" to calendarFixtureUrl), "demo-calendar",
        )
        assertEquals(calendarFixtureUrl, issued.url)
    }
}
