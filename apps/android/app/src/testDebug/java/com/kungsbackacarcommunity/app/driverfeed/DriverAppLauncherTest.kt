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
    @Test fun launcherTargetsOnlyDriverAndCarriesNoMemberOrPostData() {
        var launched: Intent? = null
        val context = launchContext { launched = it }

        assertTrue(openDriverApp(context))

        val intent = requireNotNull(launched)
        assertEquals("com.kungsbackacarcommunity.driver", intent.`package`)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(setOf(Intent.CATEGORY_LAUNCHER), intent.categories)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertNull(intent.data)
        assertNull(intent.extras)
        assertNull(intent.clipData)
        assertNull(intent.selector)
        assertNull(intent.component)
    }

    @Test fun missingOrDisabledAppReturnsFailureWithoutOpeningAnotherTarget() {
        var attempts = 0
        val context = launchContext {
            attempts++
            throw ActivityNotFoundException()
        }
        assertFalse(openDriverApp(context))
        assertEquals(1, attempts)
    }

    @Test fun blockedActivityReturnsFailureWithoutCrashing() {
        val context = launchContext { throw SecurityException() }
        assertFalse(openDriverApp(context))
    }

    private fun launchContext(launch: (Intent) -> Unit): Context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun startActivity(intent: Intent) = launch(intent)
        }
}
