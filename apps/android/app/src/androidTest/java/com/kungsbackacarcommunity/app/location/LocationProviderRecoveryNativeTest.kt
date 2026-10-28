package com.kungsbackacarcommunity.app.location

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.kungsbackacarcommunity.app.live.BindLivePositionPublisher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Emulator-only: changes the real global location switch and restores it in finally.
 * Run alone on its emulator. No Firebase repository, GPS request or FGS is created.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class LocationProviderRecoveryNativeTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(
        GrantPermissionRule.grant(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
    ).around(compose)

    @Test(timeout = 60_000)
    fun actualProviderSwitchStartsOnlyAfterOnWithoutResumeOrRecoveryKeyChange() {
        assumeTrue("Changes the location master switch: run on an owned emulator only",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalEnabled = readSwitch()
        val probe = mutableIntStateOf(0)
        val lastAccess = AtomicReference<LocationAccess>()
        val providerCallbacks = CopyOnWriteArrayList<LocationAccess>()
        val starts = CopyOnWriteArrayList<LocationAccess>()
        val lifecycleEvents = CopyOnWriteArrayList<Lifecycle.Event>()
        val lifecycleObserver = LifecycleEventObserver { _, event -> lifecycleEvents += event }
        instrumentation.runOnMainSync { compose.activity.lifecycle.addObserver(lifecycleObserver) }
        try {
            writeSwitch(false)
            compose.waitUntil(timeoutMillis = 10_000) { !readSwitch() }
            assertTrue("GrantPermissionRule must grant precise access", hasFineLocationPermission(compose.activity))
            compose.setContent {
                val context = LocalContext.current
                ObserveLocationProviderChanges {
                    providerCallbacks += currentLocationAccess(context)
                    probe.intValue++
                }
                val access = remember(probe.intValue, context) { currentLocationAccess(context) }
                SideEffect { lastAccess.set(access) }
                // Literal constant: provider recovery must come through locationAccess,
                // without a permission result, lifecycle resume, or recovery-key change.
                BindLivePositionPublisher(true, "local-member", "local-session", access, recoveryKey = 0) {
                    starts += access
                }
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                lastAccess.get() == LocationAccess.SERVICES_OFF && providerCallbacks.contains(LocationAccess.SERVICES_OFF)
            }
            compose.runOnIdle {
                assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
                assertTrue("OFF must not start the publisher", starts.isEmpty())
            }
            val beforeEnableEvents = lifecycleEvents.toList()
            val beforeEnableCallbacks = providerCallbacks.size
            writeSwitch(true) // System sends the protected provider broadcasts; no synthetic Intent.
            compose.waitUntil(timeoutMillis = 10_000) {
                lastAccess.get() == LocationAccess.GRANTED && starts.size == 1 &&
                    providerCallbacks.size > beforeEnableCallbacks
            }
            compose.runOnIdle {
                assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
                assertEquals("No pause/start/resume transition may drive recovery", beforeEnableEvents, lifecycleEvents.toList())
                assertEquals(listOf(LocationAccess.GRANTED), starts.toList())
                assertEquals(LocationAccess.GRANTED, providerCallbacks.last())
                assertEquals(LocationAccess.GRANTED, currentLocationAccess(compose.activity))
            }
        } finally {
            try {
                writeSwitch(originalEnabled)
                compose.waitUntil(timeoutMillis = 10_000) { readSwitch() == originalEnabled }
            } finally {
                instrumentation.runOnMainSync { compose.activity.lifecycle.removeObserver(lifecycleObserver) }
            }
        }
    }

    private fun readSwitch(): Boolean = when (val output = shell("cmd location is-location-enabled").trim()) {
        "true" -> true
        "false" -> false
        else -> error("cmd location did not report a Boolean: $output")
    }

    private fun writeSwitch(enabled: Boolean) {
        shell("cmd location set-location-enabled $enabled")
        // The following bounded state assertion verifies success, independently
        // of whether a platform version prints a success message.
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
