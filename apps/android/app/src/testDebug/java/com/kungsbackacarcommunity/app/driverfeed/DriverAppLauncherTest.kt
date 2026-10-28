package com.kungsbackacarcommunity.app.driverfeed

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriverAppLauncherTest {
    @Test fun exactPostGoesOnlyToTheVerifiedDriverPackage() {
        var launched: Intent? = null
        assertTrue(openDriverPost(launchContext { launched = it }, "thread_12345678", ""))
        val intent = requireNotNull(launched)
        assertEquals(DRIVER_ANDROID_PACKAGE, intent.`package`)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("kcc-driver://post/thread_12345678", intent.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.flags)
        assertNull(intent.extras)
        assertNull(intent.clipData)
        assertNull(intent.selector)
        assertNull(intent.component)
    }

    @Test fun supportedBoundaryIdsRoundTripAsExactlyOnePathComponent() {
        for (id in listOf("Abcd_123", "a".repeat(128), "carcommunity_event_12345678")) {
            assertEquals(id, requireNotNull(driverPostUri(id)).pathSegments.single())
        }
    }

    @Test fun invalidIdsNeverLaunchOrFallBack() {
        val invalid = listOf("", "short", "a".repeat(129), "../users", "thread/a", "thread?a", "thread#a",
            "thread%2Fother", "thread\n1", "thread å", "thread:1")
        for (id in invalid) {
            assertNull(driverPostUri(id))
            assertFalse(openDriverPost(launchContext { fail("Invalid ID launched") }, id,
                "https://downloads.example.test/driver"))
        }
    }

    @Test fun explicitlyDisabledWebsiteReturnsFailureWithoutAnUnrestrictedPostLaunch() {
        var attempts = 0
        assertFalse(openDriverPost(launchContext { attempts++; throw ActivityNotFoundException() },
            "thread_12345678", ""))
        assertEquals(1, attempts)
    }

    @Test fun missingOrOlderHandlerUsesTheApprovedWebsiteByDefaultWithoutPostData() {
        val intents = mutableListOf<Intent>()
        val context = launchContext {
            intents += it
            if (intents.size == 1) throw ActivityNotFoundException()
        }
        assertTrue(openDriverPost(context, "thread_12345678"))
        assertEquals(2, intents.size)
        assertEquals(DRIVER_ANDROID_PACKAGE, intents.first().`package`)
        assertEquals("kcc-driver://post/thread_12345678", intents.first().dataString)
        val website = intents.last()
        assertEquals("https://kungsbackacarcommunity.se/", website.dataString)
        assertEquals(Intent.ACTION_VIEW, website.action)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), website.categories)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, website.flags)
        assertNull(website.`package`)
        assertNull(website.extras)
        assertNull(website.clipData)
        assertNull(website.selector)
    }

    @Test fun configuredWebsiteReceivesNoPostIdOrAccountData() {
        val intents = mutableListOf<Intent>()
        val context = launchContext {
            intents += it
            if (intents.size == 1) throw ActivityNotFoundException()
        }
        // Reserved example domain is a test fixture, not a configured production destination.
        val download = "https://downloads.example.test/driver?platform=android"
        assertTrue(openDriverPost(context, "thread_12345678", download))
        assertEquals(2, intents.size)
        assertEquals(download, intents.last().dataString)
        assertNull(intents.last().`package`)
        assertNull(intents.last().extras)
        assertNull(intents.last().clipData)
    }

    @Test fun unsafeWebsiteConfigurationsAreRejected() {
        for (url in listOf("", "http://example.test", "javascript:alert(1)", "intent://driver",
            "https://user:pass@example.test/driver", "https://example.test/driver#fragment", "https:///missing")) {
            assertNull(driverFallbackUri(url))
        }
    }

    @Test fun missingBrowserOrStoreAndSecurityRejectionDoNotCrash() {
        assertFalse(openDriverPost(launchContext { throw ActivityNotFoundException() }, "thread_12345678",
            "https://downloads.example.test/driver"))
        assertFalse(openDriverPost(launchContext { throw SecurityException() }, "thread_12345678", ""))
    }

    private fun launchContext(launch: (Intent) -> Unit): Context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun startActivity(intent: Intent) = launch(intent)
        }
}
